package com.gestaosla.backend.outbox.api;

import java.util.UUID;

/** Tabela de saída (outbox, RF-49, RN-113). */
public interface OutboxService {

    /**
     * Grava o evento na transação de quem chama: se ela der rollback, o evento não existe (RN-113). Chamado fora de uma
     * transação, lança {@link org.springframework.transaction.IllegalTransactionStateException}. Gravar de novo um
     * {@code eventoId} existente é ignorado, sem erro.
     */
    void registrar(EventoSaida evento);

    /**
     * Grava a notificação na exchange {@code notificacoes}, com a routing key do canal do tipo (RN-52). O payload é o
     * record em JSON mais o {@code eventoId}, conforme o schema em {@code contratos/notificacoes/}. Mesmas regras de
     * {@link #registrar(EventoSaida)}.
     */
    void registrar(EventoNotificacao evento);

    /** Com {@code eventoId} determinístico: violação de SLA, composto por chamado, relógio e marco inicial (RN-54). */
    void registrar(EventoNotificacao evento, UUID eventoId);
}
