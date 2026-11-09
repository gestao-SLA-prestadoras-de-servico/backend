package com.gestaosla.backend.outbox.api;

/** Tabela de saída (outbox, RF-49, RN-113). */
public interface OutboxService {

    /**
     * Grava o evento na transação de quem chama: se ela der rollback, o evento não existe (RN-113). Chamado fora de uma
     * transação, lança {@link org.springframework.transaction.IllegalTransactionStateException}. Gravar de novo um
     * {@code eventoId} existente é ignorado, sem erro.
     */
    void registrar(EventoSaida evento);
}
