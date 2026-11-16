package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.chamado.api.AtendimentoConsulta;
import com.gestaosla.backend.chamado.api.AtendimentoCorrente;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;

import lombok.RequiredArgsConstructor;

/**
 * Atendimentos do chamado. A escrita roda na transação da operação do chamado (atribuição, reabertura, reatribuição,
 * cancelamento). O índice único parcial garante no máximo um corrente por chamado, também em corrida.
 */
@Service
@RequiredArgsConstructor
class AtendimentoService implements AtendimentoConsulta {

    private static final List<EstadoAtendimento> CORRENTES = List.of(EstadoAtendimento.PENDENTE, EstadoAtendimento.EM_ANDAMENTO);

    private final AtendimentoRepository atendimentos;
    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final RelogiosSla relogios;
    private final NotificadorViolacao notificador;
    private final Clock clock;

    /**
     * O técnico atribuído chegou ao local (RF-22): ATRIBUIDO passa a EM_ATENDIMENTO e o atendimento PENDENTE (criado na
     * reabertura) começa; sem ele, um novo é criado. Cumpre o SLA de campo (RN-24) só no primeiro início do ciclo: depois
     * de uma reatribuição, o sla ignora o segundo cumprimento (RN-22).
     */
    @Transactional
    void iniciar(UUID chamadoId, Ator tecnico) {
        Chamado chamado = doTecnico(chamadoId, tecnico);
        transicao.transicionar(chamado, EstadoChamado.EM_ATENDIMENTO, tecnico);
        Instant agora = Instant.now(clock);
        Atendimento atendimento = atendimentos.findByChamadoIdAndEstadoIn(chamadoId, List.of(EstadoAtendimento.PENDENTE))
                .orElseGet(() -> atendimentos.save(new Atendimento(chamadoId, tecnico.sub())));
        atendimento.iniciar(agora);
        relogios.cumprir(chamadoId, TipoRelogio.CAMPO, agora).violacao().ifPresent(v -> notificador.notificar(chamado, List.of(v)));
    }

    /** Descrição técnica do que foi executado (RF-23), só com o atendimento em andamento. */
    @Transactional
    void registrarDescricao(UUID chamadoId, Ator tecnico, String descricao) {
        doTecnico(chamadoId, tecnico);
        atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES)
                .orElseThrow(() -> new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA, "O chamado não tem atendimento em andamento."))
                .descrever(descricao);
    }

    /** Chamado atribuído ao técnico do token; para qualquer outro, inexistente (RN-126). */
    private Chamado doTecnico(UUID chamadoId, Ator tecnico) {
        return chamados.findById(chamadoId).filter(c -> tecnico.sub().equals(c.getTecnicoSub()))
                .orElseThrow(RecursoNaoEncontradoException::new);
    }

    /** Novo atendimento PENDENTE do técnico, em cada reabertura (RN-95). */
    @Transactional(propagation = Propagation.MANDATORY)
    Atendimento criarPendente(UUID chamadoId, UUID tecnicoSub) {
        return atendimentos.save(new Atendimento(chamadoId, tecnicoSub));
    }

    /** Interrompe o corrente, se houver, preservando as evidências (RN-21). */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Atendimento> interromperCorrente(UUID chamadoId, Instant instante) {
        Optional<Atendimento> corrente = atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES);
        corrente.ifPresent(a -> a.interromper(instante));
        return corrente;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AtendimentoCorrente> correnteDe(UUID chamadoId) {
        return atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES).map(Atendimento::corrente);
    }

    @Transactional(readOnly = true)
    List<Atendimento> doChamado(UUID chamadoId) {
        return atendimentos.findByChamadoIdOrderByCreatedAtAscIdAsc(chamadoId);
    }
}
