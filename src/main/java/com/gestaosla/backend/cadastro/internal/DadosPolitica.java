package com.gestaosla.backend.cadastro.internal;

import com.gestaosla.backend.cadastro.api.Prioridade;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo do POST de política: o nome e a versão 1. */
record DadosPolitica(
        @Schema(example = "Contrato Ouro") @NotBlank @Size(max = 100) String nome,
        @NotNull Prioridade prioridadePadrao,
        @NotNull @Valid PrazosPorPrioridade prazos) {

    DadosVersao versao() {
        return new DadosVersao(prioridadePadrao, prazos);
    }
}
