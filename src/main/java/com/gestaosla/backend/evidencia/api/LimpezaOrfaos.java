package com.gestaosla.backend.evidencia.api;

import java.time.Instant;
import java.util.function.IntConsumer;

/** Informa a contagem de objetos removidos em cada página. */
public interface LimpezaOrfaos {
    void executar(Instant agora, int tamanho, IntConsumer contabilizar);
}
