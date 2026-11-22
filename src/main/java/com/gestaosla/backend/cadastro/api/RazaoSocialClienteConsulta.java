package com.gestaosla.backend.cadastro.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Consulta em lote das razões sociais para exibir relatórios legíveis sem cruzar tabelas de outros módulos. */
public interface RazaoSocialClienteConsulta {

    Map<UUID, String> razoesSociais(Collection<UUID> clienteIds);
}
