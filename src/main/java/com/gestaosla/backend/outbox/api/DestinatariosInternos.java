package com.gestaosla.backend.outbox.api;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Subs de técnicos e gestores de uma notificação interna. Só o {@link MontadorDestinatarios} cria, depois de conferir que
 * nenhum é de cliente (RN-41). Na mensagem, vira a lista de subs.
 */
public final class DestinatariosInternos {

    private final List<UUID> subs;

    DestinatariosInternos(List<UUID> subs) {
        this.subs = List.copyOf(subs);
    }

    @JsonValue
    public List<UUID> subs() {
        return subs;
    }
}
