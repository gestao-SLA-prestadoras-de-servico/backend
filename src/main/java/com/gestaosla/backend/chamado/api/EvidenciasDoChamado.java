package com.gestaosla.backend.chamado.api;

import java.util.Map;
import java.util.UUID;

/**
 * Resumo das evidências do chamado, para a consulta. O chamado declara a porta e o módulo evidencia a implementa: o
 * evidencia depende do chamado (atendimento corrente), nunca o contrário. Até ele existir, a contagem é 0.
 */
public interface EvidenciasDoChamado {

    /** Quantidade de evidências por atendimento do chamado; atendimento sem evidência pode ficar fora do mapa. */
    Map<UUID, Long> contarPorAtendimento(UUID chamadoId);
}
