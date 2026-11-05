package com.gestaosla.backend.cadastro.internal;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Embeddable
public record Endereco(
        @Schema(example = "Av. Santos Dumont") @NotBlank @Size(max = 200) String logradouro,
        @Schema(example = "1500") @NotBlank @Size(max = 20) String numero,
        @Schema(example = "Sala 301") @Size(max = 100) String complemento,
        @Schema(example = "Aldeota") @NotBlank @Size(max = 100) String bairro,
        @Schema(example = "Fortaleza") @NotBlank @Size(max = 100) String cidade,
        @Schema(example = "CE") @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "deve ser a sigla do estado, ex.: CE") String uf,
        @Schema(example = "60150160") @NotBlank @Pattern(regexp = "\\d{8}", message = "deve ter 8 dígitos, sem traço") String cep) {
}
