package com.gestaosla.backend.cadastro.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo do POST de contrato (RF-06). Fim antes do início → 400 VALIDACAO no campo fimVigencia. Público só para o teste em unit/. */
public record DadosContrato(
        @NotNull UUID clienteId,
        @Schema(example = "CT-2026-001") @NotBlank @Size(max = 50) String codigo,
        @Schema(example = "2026-01-01") @NotNull LocalDate inicioVigencia,
        @Schema(example = "2026-12-31") @NotNull LocalDate fimVigencia) {

    // O nome "fimVigencia" faz o erro sair nesse campo; datas nulas já são recusadas pelo @NotNull.
    @AssertTrue(message = "não pode ser anterior ao início da vigência")
    @Schema(hidden = true)
    boolean isFimVigencia() {
        return inicioVigencia == null || fimVigencia == null || !fimVigencia.isBefore(inicioVigencia);
    }
}
