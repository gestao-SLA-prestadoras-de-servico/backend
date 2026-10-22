package com.gestaosla.backend.shared.api;

import lombok.Getter;

/** Violação de regra de negócio. Sai como 422 com o código informado. */
@Getter
public class RegraNegocioException extends RuntimeException {

    private final CodigoErro codigo;

    public RegraNegocioException(CodigoErro codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }
}
