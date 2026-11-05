package com.gestaosla.backend.identidade.api;

import java.util.UUID;

import com.gestaosla.backend.shared.api.Papel;

/**
 * Usuário da requisição, montado a partir do token. Injetar com {@code @AuthenticationPrincipal UsuarioCorrente}.
 *
 * @param papel           papel do token, nunca a cópia local do perfil (RN-130); {@code null} se o token não tiver nenhum
 * @param empresaClienteId empresa do usuário de perfil CLIENTE, do registro local; {@code null} para técnico e gestor
 */
public record UsuarioCorrente(UUID sub, Papel papel, UUID empresaClienteId) {
}
