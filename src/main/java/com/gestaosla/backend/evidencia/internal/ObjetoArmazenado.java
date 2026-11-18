package com.gestaosla.backend.evidencia.internal;

import java.time.Instant;

record ObjetoArmazenado(String chave, Instant ultimaModificacao, long tamanho) {
}
