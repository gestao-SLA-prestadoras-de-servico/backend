package com.gestaosla.backend.shared.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Quem fez a operação: um usuário (sub do Keycloak e papel do token, RN-129, RN-130) ou o próprio sistema
 * (rotinas agendadas, consumidores de fila). Quem chama a auditoria o informa; o módulo auditoria não lê o token.
 */
public record Ator(UUID sub, Papel papel) {

    public static final Ator SISTEMA = new Ator(null, null);

    public Ator {
        if ((sub == null) != (papel == null)) {
            throw new IllegalArgumentException("Ator de usuário precisa de sub e papel; o do sistema, de nenhum.");
        }
    }

    public static Ator usuario(UUID sub, Papel papel) {
        return new Ator(Objects.requireNonNull(sub), Objects.requireNonNull(papel));
    }

    public boolean sistema() {
        return sub == null;
    }
}
