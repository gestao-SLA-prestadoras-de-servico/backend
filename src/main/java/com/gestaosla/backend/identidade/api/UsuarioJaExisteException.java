package com.gestaosla.backend.identidade.api;

import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.ConflitoException;

/** Já existe conta no Keycloak com o mesmo username ou e-mail (RN-127). Sai como 409 {@code USUARIO_JA_EXISTE}. */
public class UsuarioJaExisteException extends ConflitoException {

    public UsuarioJaExisteException() {
        super(CodigoErro.USUARIO_JA_EXISTE, "Já existe uma conta com este username ou e-mail.");
    }
}
