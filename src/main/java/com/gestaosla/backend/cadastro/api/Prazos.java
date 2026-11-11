package com.gestaosla.backend.cadastro.api;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Positive;

/** Prazos dos três relógios de uma prioridade, em minutos de janela (RN-28, RN-29). */
@Embeddable
public record Prazos(
        @Schema(example = "120") @Positive int resposta,
        @Schema(example = "540") @Positive int campo,
        @Schema(example = "1080") @Positive int resolucao) {

    /** RN-30: resposta &lt; campo &lt; resolução. Vale para a configuração, não para a ordem dos eventos. */
    public boolean respeitaOrdem() {
        return resposta < campo && campo < resolucao;
    }
}
