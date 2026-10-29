package com.gestaosla.backend.shared.api;

import lombok.Getter;

/** Conflito com um registro que já existe (ex.: conta duplicada no Keycloak). Sai como 409 com o código informado. */
@Getter
public class ConflitoException extends RuntimeException {

    private final CodigoErro codigo;

    public ConflitoException(CodigoErro codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }
}
