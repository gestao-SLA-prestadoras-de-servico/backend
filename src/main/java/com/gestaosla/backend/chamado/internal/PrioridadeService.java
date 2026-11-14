package com.gestaosla.backend.chamado.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.Violacao;

import lombok.RequiredArgsConstructor;

/** Prioridade definida pelo gestor (RF-15, RN-15): recalcula os prazos que ainda correm (RF-31, RN-17, RN-18). */
@Service
@RequiredArgsConstructor
class PrioridadeService {

    // Decisão da [chamado - 05]: só antes de RESOLVIDO.
    private static final Set<EstadoChamado> PERMITIDOS = Set.of(EstadoChamado.ABERTO, EstadoChamado.ATRIBUIDO,
            EstadoChamado.EM_ATENDIMENTO);

    private final ChamadoRepository chamados;
    private final ContratoConsulta contratos;
    private final RelogiosSla relogios;
    private final NotificadorViolacao notificador;
    private final AuditoriaService auditoria;
    private final HistoricoChamado historico;

    @Transactional
    Chamado alterar(UUID chamadoId, Prioridade nova, Ator gestor) {
        Chamado chamado = chamados.findById(chamadoId).orElseThrow(RecursoNaoEncontradoException::new);
        if (!PERMITIDOS.contains(chamado.getEstado())) {
            throw new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA,
                    "A prioridade não pode ser alterada com o chamado em " + chamado.getEstado() + ".");
        }
        Prioridade anterior = chamado.prioridadeEfetiva();
        if (anterior == nova) {
            return chamado; // nada muda: sem gravação, recálculo nem auditoria
        }
        chamado.definirPrioridade(nova);
        // A versão guardada na abertura, não a vigente do contrato (RN-05).
        List<Violacao> violacoes = relogios.recalcularPorPrioridade(chamadoId,
                contratos.versaoDaPolitica(chamado.getPoliticaVersaoId()), nova);
        notificador.notificar(chamado, violacoes);
        Map<String, Object> dados = Map.of("anterior", anterior.name(), "nova", nova.name());
        auditoria.registrar(gestor, OperacaoAuditada.ALTERACAO_PRIORIDADE, EntidadeAuditada.CHAMADO, chamadoId, chamadoId, dados);
        historico.registrar(chamadoId, TipoEventoHistorico.ALTERACAO_PRIORIDADE, gestor, dados);
        return chamado;
    }
}
