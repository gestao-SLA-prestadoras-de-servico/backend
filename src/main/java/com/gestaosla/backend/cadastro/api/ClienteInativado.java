package com.gestaosla.backend.cadastro.api;

import java.util.UUID;

/**
 * Publicado na transação que inativa o cliente. A [autenticação - 06] o escuta para inativar os usuários do cliente,
 * sem o cadastro depender do identidade.
 */
public record ClienteInativado(UUID clienteId) {
}
