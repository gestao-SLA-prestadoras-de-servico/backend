package com.gestaosla.backend.cadastro.api;

import java.util.UUID;

/** O que outros módulos podem saber de um contrato. */
public interface ContratoConsulta {

    /**
     * Versão mais recente da política vinculada ao contrato. O chamado guarda o {@code versaoId} na abertura: trocar a
     * política ou criar uma versão nova depois só afeta chamados abertos depois (RN-05).
     *
     * @throws com.gestaosla.backend.shared.api.RecursoNaoEncontradoException contrato inexistente
     * @throws com.gestaosla.backend.shared.api.RegraNegocioException         {@code CONTRATO_SEM_POLITICA} (RN-03)
     */
    PoliticaDoContrato versaoVigenteDaPolitica(UUID contratoId);

    /**
     * Uma versão específica de política, pelo id que o chamado guardou na abertura. É a que vale para recalcular os
     * prazos do chamado e para a reabertura, mesmo que a política já tenha versões mais novas (RN-05).
     *
     * @throws com.gestaosla.backend.shared.api.RecursoNaoEncontradoException versão inexistente
     */
    PoliticaDoContrato versaoDaPolitica(UUID versaoId);

    /**
     * Confere se o cliente pode abrir chamado sob o contrato hoje e devolve a versão vigente da política, que o chamado
     * guarda (RN-05). Contrato de outra empresa responde como inexistente (RN-126).
     *
     * @throws com.gestaosla.backend.shared.api.RecursoNaoEncontradoException contrato inexistente ou de outra empresa
     * @throws com.gestaosla.backend.shared.api.RegraNegocioException         {@code CONTRATO_FORA_VIGENCIA} (RN-01) ou
     *                                                                        {@code CONTRATO_SEM_POLITICA} (RN-03)
     */
    PoliticaDoContrato politicaParaAbertura(UUID contratoId, UUID empresaClienteId);
}
