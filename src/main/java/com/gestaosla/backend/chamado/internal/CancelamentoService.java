package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import lombok.RequiredArgsConstructor;

/**
 * Cancelamento pelo gestor, com motivo, antes da resolução (RF-18, RN-19). Cancelado não tem apuração de SLA e passa a
 * contar a retenção das evidências (RN-100, pelo {@code estado_final_em}). O técnico não é avisado (decisão da task).
 */
@Service
@RequiredArgsConstructor
class CancelamentoService {

    private static final Set<EstadoChamado> PERMITIDOS = Set.of(EstadoChamado.ABERTO, EstadoChamado.ATRIBUIDO,
            EstadoChamado.EM_ATENDIMENTO);

    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final AtendimentoService atendimentos;
    private final AuditoriaService auditoria;
    private final Clock clock;

    @Transactional
    void cancelar(UUID chamadoId, String motivo, Ator gestor) {
        Chamado chamado = chamados.findById(chamadoId).orElseThrow(RecursoNaoEncontradoException::new);
        if (!PERMITIDOS.contains(chamado.getEstado())) {
            throw new RegraNegocioException(CodigoErro.CANCELAMENTO_NAO_PERMITIDO,
                    "Chamado " + chamado.getEstado() + " não pode ser cancelado (RN-19).");
        }
        chamado.registrarMotivoCancelamento(motivo);
        transicao.transicionar(chamado, EstadoChamado.CANCELADO, gestor);
        // O atendimento em andamento é interrompido, com as evidências preservadas.
        atendimentos.interromperCorrente(chamadoId, Instant.now(clock));
        auditoria.registrar(gestor, OperacaoAuditada.CANCELAMENTO, EntidadeAuditada.CHAMADO, chamadoId, chamadoId,
                Map.of("motivo", motivo));
    }
}
