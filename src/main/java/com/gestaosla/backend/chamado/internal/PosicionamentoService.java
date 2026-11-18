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
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;

import lombok.RequiredArgsConstructor;

/**
 * Primeiro posicionamento do gestor ao cliente (RF-16): cumpre o SLA de resposta (RN-23) e vai por e-mail ao cliente
 * (RN-33). Vale em qualquer estado não final, porque os eventos podem acontecer fora de ordem (RN-30).
 */
@Service
@RequiredArgsConstructor
class PosicionamentoService {

    private final ChamadoRepository chamados;
    private final RelogiosSla relogios;
    private final NotificadorViolacao notificador;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;
    private final HistoricoChamado historico;
    private final AuditoriaService auditoria;
    private final Clock clock;

    @Transactional
    void registrar(UUID chamadoId, String texto, Ator gestor) {
        Chamado chamado = chamados.findById(chamadoId).orElseThrow(RecursoNaoEncontradoException::new);
        if (chamado.getEstado().finalizado()) {
            throw new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA,
                    "Chamado " + chamado.getEstado() + " não aceita posicionamento.");
        }
        Instant agora = Instant.now(clock);
        chamado.posicionar(texto, agora);
        relogios.cumprir(chamadoId, TipoRelogio.RESPOSTA, agora).violacao()
                .ifPresent(v -> notificador.notificar(chamado, List.of(v)));
        // Na outbox, na mesma transação: rollback não gera e-mail (RN-52, RN-113).
        outbox.registrar(new EventoNotificacao.Posicionamento(new EventoNotificacao.Chamado(chamadoId, chamado.getNumero()),
                chamado.getTitulo(), destinatarios.emailDoCliente(chamado.getClienteId()), texto));
        historico.registrar(chamadoId, TipoEventoHistorico.POSICIONAMENTO, gestor, Map.of());
        // Sem o texto: a trilha é imutável por 5 anos e não guarda texto livre (RN-122).
        auditoria.registrar(gestor, OperacaoAuditada.PRIMEIRO_POSICIONAMENTO, EntidadeAuditada.CHAMADO, chamadoId, chamadoId, Map.of());
    }
}
