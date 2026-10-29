package com.gestaosla.backend.identidade.api;

import com.gestaosla.backend.shared.api.ServicoIndisponivelException;

/**
 * Keycloak fora do ar, lento além do timeout ou respondendo 5xx (RN-137). É a única falha que o retry repete.
 */
public class IdpIndisponivelException extends ServicoIndisponivelException {

    public IdpIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
