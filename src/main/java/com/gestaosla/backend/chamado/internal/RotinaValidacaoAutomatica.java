package com.gestaosla.backend.chamado.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.sla.api.CalendarioJanela;

import lombok.RequiredArgsConstructor;

/**
 * Validação automática (RF-35, RN-92): chamado RESOLVIDO há 3 dias úteis (1620 minutos de janela) sem resposta do
 * cliente é encerrado, com o aviso por e-mail 1 dia útil antes (RN-33, RN-34). Roda de hora em hora. Se o aviso saiu
 * atrasado (sistema fora), o encerramento espera 1 dia útil depois dele: o cliente sempre tem esse dia (decisão do
 * usuário). Lotes com uma transação cada (RN-119); o chamado que o cliente está validando ou reabrindo é pulado.
 */
@Component
@RequiredArgsConstructor
class RotinaValidacaoAutomatica {

    static final int TAMANHO_LOTE = 100;
    static final long MINUTOS_ATE_AVISO = 1080;
    static final long MINUTOS_ATE_ENCERRAMENTO = 1620;
    static final long UM_DIA_UTIL = 540;

    private final ChamadoRepository chamados;
    private final ValidacaoService validacao;
    private final CalendarioJanela calendario;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;
    private final AuditoriaService auditoria;
    private final TransactionTemplate transacao;
    private final Clock clock;

    private record Cursor(Instant resolvidoEm, UUID id) {
    }

    @Scheduled(cron = "${app.chamado.validacao-automatica.cron}")
    void executar() {
        executar(TAMANHO_LOTE);
    }

    void executar(int tamanhoLote) {
        Instant agora = Instant.now(clock);
        Cursor cursor = new Cursor(Instant.EPOCH, new UUID(0, 0));
        while (cursor != null) {
            Cursor apos = cursor;
            cursor = transacao.execute(s -> lote(agora, apos, tamanhoLote));
        }
    }

    /** @return o cursor do próximo lote, ou {@code null} se este veio incompleto */
    private Cursor lote(Instant agora, Cursor apos, int tamanhoLote) {
        List<Chamado> resolvidos = chamados.travarResolvidos(apos.resolvidoEm(), apos.id(),
                Limit.of(tamanhoLote));
        for (Chamado chamado : resolvidos) {
            processar(chamado, agora);
        }
        if (resolvidos.size() < tamanhoLote) {
            return null;
        }
        Chamado ultimo = resolvidos.getLast();
        return new Cursor(ultimo.getResolvidoEm(), ultimo.getId());
    }

    private void processar(Chamado chamado, Instant agora) {
        Instant limite = calendario.adicionarMinutos(chamado.getResolvidoEm(), MINUTOS_ATE_ENCERRAMENTO);
        if (chamado.getAvisoEnviadoEm() == null) {
            if (!agora.isBefore(calendario.adicionarMinutos(chamado.getResolvidoEm(), MINUTOS_ATE_AVISO))) {
                avisar(chamado, maior(limite, calendario.adicionarMinutos(agora, UM_DIA_UTIL)), agora);
            }
            return;
        }
        if (!agora.isBefore(maior(limite, calendario.adicionarMinutos(chamado.getAvisoEnviadoEm(), UM_DIA_UTIL)))) {
            encerrar(chamado);
        }
    }

    private void avisar(Chamado chamado, Instant encerramentoPrevistoEm, Instant agora) {
        outbox.registrar(new EventoNotificacao.AvisoEncerramento(
                new EventoNotificacao.Chamado(chamado.getId(), chamado.getNumero()), chamado.getTitulo(),
                destinatarios.emailDoCliente(chamado.getClienteId()), encerramentoPrevistoEm), eventoId(chamado.getId(), chamado.getResolvidoEm()));
        chamado.registrarAviso(agora);
    }

    private void encerrar(Chamado chamado) {
        Map<String, Object> dados = Map.of("resolvidoEm", chamado.getResolvidoEm().toString(),
                "avisoEnviadoEm", chamado.getAvisoEnviadoEm().toString());
        validacao.encerrar(chamado, Ator.SISTEMA);
        auditoria.registrar(Ator.SISTEMA, OperacaoAuditada.VALIDACAO_AUTOMATICA, EntidadeAuditada.CHAMADO,
                chamado.getId(), chamado.getId(), dados);
    }

    /** Um aviso por resolução: a reabertura seguida de nova resolução muda o {@code resolvidoEm} e gera outro. */
    static UUID eventoId(UUID chamadoId, Instant resolvidoEm) {
        String chave = "aviso-encerramento:" + chamadoId + ":" + resolvidoEm;
        return UUID.nameUUIDFromBytes(chave.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant maior(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
