package com.gestaosla.backend.identidade.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Consulta em lote dos nomes locais dos técnicos, indexados pelo {@code sub} do Keycloak. */
public interface NomeTecnicoConsulta {

    Map<UUID, String> nomesTecnicos(Collection<UUID> subs);
}
