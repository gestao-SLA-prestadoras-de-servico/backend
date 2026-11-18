package com.gestaosla.backend.chamado.api;

import java.util.Optional;
import java.util.UUID;

/** O que outros módulos (o evidencia) podem saber dos atendimentos de um chamado. */
public interface AtendimentoConsulta {

    /** Atendimento PENDENTE ou EM_ANDAMENTO do chamado; vazio se não houver. */
    Optional<AtendimentoCorrente> correnteDe(UUID chamadoId);
}
