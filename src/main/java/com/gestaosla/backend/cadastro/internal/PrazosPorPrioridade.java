package com.gestaosla.backend.cadastro.internal;

import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Os 6 prazos de uma versão da política. Público só para o teste em unit/. */
public record PrazosPorPrioridade(@NotNull @Valid Prazos alta, @NotNull @Valid Prazos normal) {

    /** RN-32: a ordem de RN-30 vale em cada prioridade; senão, 422 ORDEM_PRAZOS_INVALIDA. */
    public void validarOrdem() {
        exigirOrdem(Prioridade.ALTA, alta);
        exigirOrdem(Prioridade.NORMAL, normal);
    }

    private static void exigirOrdem(Prioridade prioridade, Prazos prazos) {
        if (!prazos.respeitaOrdem()) {
            throw new RegraNegocioException(CodigoErro.ORDEM_PRAZOS_INVALIDA, "Prioridade " + prioridade
                    + ": o prazo de resposta deve ser menor que o de atendimento em campo, e este menor que o de resolução.");
        }
    }
}
