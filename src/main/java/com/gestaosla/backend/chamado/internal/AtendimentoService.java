package com.gestaosla.backend.chamado.internal;

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

    /** Novo atendimento PENDENTE do técnico: na atribuição e em cada reabertura (RN-95). */
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
