package com.gestaosla.backend.cadastro.internal;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo do POST e base do PUT de cliente (RN-08). E-mail inválido → 400 VALIDACAO. Público só para o teste em unit/. */
public record DadosCliente(
        @Schema(example = "Refrigeração Norte Ltda") @NotBlank @Size(max = 200) String razaoSocial,
        @Schema(example = "contato@norte.com.br") @NotBlank @Email @Size(max = 254) String emailContato,
        @NotNull @Valid Endereco endereco) {
}
