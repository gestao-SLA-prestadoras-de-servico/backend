package com.gestaosla.backend.shared.api;

/**
 * Nomes das respostas de erro do contrato OpenAPI (componentes definidos no shared). Uso no endpoint:
 * {@code @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)}. 400, 401, 403 e 500 entram sozinhos.
 */
public final class RespostasErro {

    public static final String VALIDACAO = "Validacao";
    public static final String NAO_ENCONTRADO = "NaoEncontrado";
    public static final String CONFLITO_VERSAO = "ConflitoVersao";
    public static final String CONFLITO = "Conflito";
    public static final String REGRA_NEGOCIO = "RegraNegocio";
    public static final String SERVICO_INDISPONIVEL = "ServicoIndisponivel";

    private RespostasErro() {
    }
}
