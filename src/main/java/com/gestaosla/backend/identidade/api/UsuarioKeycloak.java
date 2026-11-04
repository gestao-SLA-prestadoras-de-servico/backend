package com.gestaosla.backend.identidade.api;

import java.util.UUID;

/**
 * Representação de um usuário consultado no Keycloak.
 */
public record UsuarioKeycloak(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled
) {
}
