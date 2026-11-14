package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

/** Grava a linha do tempo do chamado, na transação da operação que a originou, como a auditoria. */
@Component
@RequiredArgsConstructor
class HistoricoChamado {

    private final EventoHistoricoRepository eventos;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    void registrar(UUID chamadoId, TipoEventoHistorico evento, Ator ator, Map<String, Object> dados) {
        eventos.save(new EventoHistorico(chamadoId, evento, ator.sub(), ator.papel(), Instant.now(clock), dados));
    }
}
