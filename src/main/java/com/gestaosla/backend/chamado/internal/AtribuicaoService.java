package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import lombok.RequiredArgsConstructor;

/**
 * Atribuição e reatribuição pelo gestor (RF-17). Trocar o técnico não toca nenhum relógio: o SLA é da prestadora, não do
 * técnico (RN-22, RN-26). A transferência por inativação ({@link TransferenciaChamados}) usa a mesma troca.
 */
@Service
@RequiredArgsConstructor
class AtribuicaoService {

    private final ChamadoRepository chamados;
    private final UsuarioConsulta usuarios;
    private final TransicaoChamado transicao;
    private final AtendimentoService atendimentos;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;
    private final HistoricoChamado historico;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /**
     * ABERTO passa a ATRIBUIDO. Em ATRIBUIDO só troca o técnico; em EM_ATENDIMENTO volta a ATRIBUIDO (RN-21). Na
     * troca, o atendimento corrente é interrompido, com as evidências preservadas.
     *
     * @param version a que o gestor leu: se o chamado mudou depois, 409 em vez de sobrescrever (RN-117)
     */
    @Transactional
    void atribuir(UUID chamadoId, UUID tecnicoSub, long version, Ator gestor) {
        Chamado chamado = chamados.findById(chamadoId).orElseThrow(RecursoNaoEncontradoException::new);
        if (chamado.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Chamado.class, chamadoId);
        }
        EstadoChamado estado = chamado.getEstado();
        if (estado != EstadoChamado.ABERTO && estado != EstadoChamado.ATRIBUIDO && estado != EstadoChamado.EM_ATENDIMENTO) {
            throw new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA, "Chamado " + estado + " não aceita atribuição (RN-20).");
        }
        if (tecnicoSub.equals(chamado.getTecnicoSub()) || !usuarios.tecnicoAtivo(tecnicoSub)) {
            throw new RegraNegocioException(CodigoErro.REATRIBUICAO_NAO_PERMITIDA,
                    "O técnico precisa ser um técnico ativo, diferente do atual.");
        }
        UUID anterior = trocarTecnico(chamado, tecnicoSub, gestor);
        if (anterior == null) {
            auditoria.registrar(gestor, OperacaoAuditada.ATRIBUICAO, EntidadeAuditada.CHAMADO, chamadoId, chamadoId,
                    Map.of("tecnico", tecnicoSub.toString()));
            historico.registrar(chamadoId, TipoEventoHistorico.ATRIBUICAO, gestor, Map.of("tecnico", tecnicoSub.toString()));
            return;
        }
        Map<String, Object> dados = Map.of("anterior", anterior.toString(), "novo", tecnicoSub.toString());
        auditoria.registrar(gestor, OperacaoAuditada.REATRIBUICAO, EntidadeAuditada.CHAMADO, chamadoId, chamadoId, dados);
        historico.registrar(chamadoId, TipoEventoHistorico.REATRIBUICAO, gestor, dados);
        // RN-37: o anterior é avisado da remoção, sem acesso ao chamado.
        outbox.registrar(new EventoNotificacao.Remocao(referencia(chamado), chamado.getTitulo(), destinatarios.internos(List.of(anterior))));
    }

    /** Troca o técnico, interrompe o atendimento corrente e avisa o novo (RN-35). @return o técnico anterior */
    UUID trocarTecnico(Chamado chamado, UUID novo, Ator ator) {
        UUID anterior = chamado.atribuir(novo);
        if (chamado.getEstado() == EstadoChamado.ABERTO) {
            transicao.transicionar(chamado, EstadoChamado.ATRIBUIDO, ator);
        } else {
            atendimentos.interromperCorrente(chamado.getId(), Instant.now(clock));
            if (chamado.getEstado() == EstadoChamado.EM_ATENDIMENTO) {
                transicao.transicionar(chamado, EstadoChamado.ATRIBUIDO, ator);
            }
        }
        outbox.registrar(new EventoNotificacao.Atribuicao(referencia(chamado), chamado.getTitulo(), destinatarios.internos(List.of(novo))));
        return anterior;
    }

    private static EventoNotificacao.Chamado referencia(Chamado chamado) {
        return new EventoNotificacao.Chamado(chamado.getId(), chamado.getNumero());
    }
}
