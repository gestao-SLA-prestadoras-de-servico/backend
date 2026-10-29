package com.gestaosla.backend.identidade.api;

import java.util.UUID;

/**
 * Usuário da requisição, montado a partir do token. Injetar com {@code @AuthenticationPrincipal UsuarioCorrente}.
 *
 * @param papel           papel do token, nunca a cópia local do perfil (RN-130); {@code null} se o token não tiver nenhum
 * @param empresaClienteId empresa do usuário cliente; sempre {@code null} até a [autenticação - 04]
 */
public record UsuarioCorrente(UUID sub, Papel papel, UUID empresaClienteId) {
}
