package com.gestaosla.backend.outbox.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * E-mail de contato do cliente, único destino de e-mail do sistema (RN-08, RN-41). Só o {@link MontadorDestinatarios}
 * cria, a partir do cadastro do cliente. Na mensagem, vira uma lista com esse e-mail.
 */
public final class DestinatarioEmail {

    private final String email;

    DestinatarioEmail(String email) {
        this.email = email;
    }

    @JsonValue
    public List<String> emails() {
        return List.of(email);
    }
}
