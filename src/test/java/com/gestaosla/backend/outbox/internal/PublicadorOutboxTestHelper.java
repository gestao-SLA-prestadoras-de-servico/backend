package com.gestaosla.backend.outbox.internal;

import org.springframework.context.ApplicationContext;

/**
 * Roda um ciclo do PublicadorOutbox a partir de {@code integration/} (o CI só roda testes nesse pacote e em
 * {@code unit/}); o publicador fica interno ao módulo. Nos testes o {@code @Scheduled} não dispara (intervalo de 1 h).
 */
public final class PublicadorOutboxTestHelper {

    private PublicadorOutboxTestHelper() {
    }

    public static void publicarPendentes(ApplicationContext contexto) {
        contexto.getBean(PublicadorOutbox.class).publicarPendentes();
    }
}
