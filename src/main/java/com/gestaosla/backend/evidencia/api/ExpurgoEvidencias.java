package com.gestaosla.backend.evidencia.api;

import java.time.Instant;
import java.util.function.IntConsumer;

/** Informa a contagem de cada lote confirmado. */
public interface ExpurgoEvidencias {
    void executar(Instant agora, int tamanho, IntConsumer contabilizar);
}
