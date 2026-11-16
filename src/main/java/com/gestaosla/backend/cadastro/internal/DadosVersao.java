package com.gestaosla.backend.cadastro.internal;

import com.gestaosla.backend.cadastro.api.Prioridade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Corpo do POST de nova versão: os prazos e a prioridade padrão (RN-16). O nome da política não muda. */
record DadosVersao(@NotNull Prioridade prioridadePadrao, @NotNull @Valid PrazosPorPrioridade prazos) {
}
