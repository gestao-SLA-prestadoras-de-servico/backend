package com.gestaosla.backend.chamado.internal;

import java.util.UUID;

import com.gestaosla.backend.cadastro.api.Prioridade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Abertura de chamado pelo cliente (RF-12). A prioridade sugerida é opcional e não muda nenhum prazo (RN-15).
 */
record DadosAbertura(
        @NotNull UUID contratoId,
        @NotBlank @Size(max = 200) String titulo,
        @NotBlank @Size(max = 4000) String descricao,
        @NotBlank @Size(max = 300) String local,
        Prioridade prioridadeSugerida) {
}
