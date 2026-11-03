package com.gestaosla.backend.identidade.internal;

import java.util.UUID;

import com.gestaosla.backend.shared.api.Papel;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo do {@code POST /api/usuarios} (RF-09). Sem campo de senha: ela é definida pelo próprio usuário no Keycloak
 * (RN-128). O e-mail também é o login. Público só para o teste em unit/.
 */
public record NovoUsuario(
        @Schema(example = "Ana Lima") @NotBlank @Size(max = 150) String nome,
        @Schema(example = "ana.lima@norte.com.br") @NotBlank @Email @Size(max = 254) String email,
        @Schema(example = "CLIENTE") @NotNull Papel perfil,
        @Schema(description = "Empresa cliente: obrigatória para CLIENTE, proibida para TECNICO e GESTOR") UUID clienteId) {
}
