package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.RelogiosSla;

import lombok.RequiredArgsConstructor;

/**
 * Reabertura pelo cliente, com justificativa (RF-21, RN-98). Não existe estado REABERTO: o chamado volta de RESOLVIDO a
 * ATRIBUIDO com o mesmo técnico (RN-93, RN-94), ganha um atendimento PENDENTE (RN-95) e um ciclo novo de campo e
 * resolução, contado da reabertura; o de resposta não muda (RN-96, RN-97).
 */
@Service
@RequiredArgsConstructor
class ReaberturaService {

    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final AtendimentoService atendimentos;
    private final RelogiosSla relogios;
    private final ContratoConsulta contratos;
    private final NotificadorViolacao notificador;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;
    private final AuditoriaService auditoria;
    private final HistoricoChamado historico;
    private final Clock clock;

    /** @param version a que o cliente leu: se o chamado mudou depois, 409 em vez de reabrir (RN-117) */
    @Transactional
    void reabrir(UUID chamadoId, String justificativa, long version, UsuarioCorrente cliente) {
        Chamado chamado = ValidacaoService.daEmpresa(chamados, chamadoId, version, cliente);
        if (chamado.getEstado() != EstadoChamado.RESOLVIDO) {
            throw new RegraNegocioException(CodigoErro.REABERTURA_NAO_PERMITIDA,
                    "Só o chamado RESOLVIDO pode ser reaberto (RN-93, RN-99).");
        }
        Ator ator = Ator.usuario(cliente.sub(), cliente.papel());
        transicao.transicionar(chamado, EstadoChamado.ATRIBUIDO, ator);
        atendimentos.criarPendente(chamadoId, chamado.getTecnicoSub());
        // A versão guardada na abertura, não a vigente do contrato (RN-05).
        notificador.notificar(chamado, relogios.reiniciarParaReabertura(chamadoId,
                contratos.versaoDaPolitica(chamado.getPoliticaVersaoId()), chamado.prioridadeEfetiva(), Instant.now(clock)));
        outbox.registrar(new EventoNotificacao.Reabertura(new EventoNotificacao.Chamado(chamadoId, chamado.getNumero()),
                chamado.getTitulo(), destinatarios.internos(List.of(chamado.getTecnicoSub()))));
        // RN-123: quem (ator) e quando (ocorrido_em) vêm da própria auditoria.
        Map<String, Object> dados = Map.of("justificativa", justificativa, "de", EstadoChamado.RESOLVIDO.name(),
                "para", EstadoChamado.ATRIBUIDO.name());
        auditoria.registrar(ator, OperacaoAuditada.REABERTURA, EntidadeAuditada.CHAMADO, chamadoId, chamadoId, dados);
        historico.registrar(chamadoId, TipoEventoHistorico.REABERTURA, ator, Map.of("justificativa", justificativa));
    }
}
