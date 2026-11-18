package com.gestaosla.backend.evidencia.internal;

import java.io.InputStream;

/** O chamador fecha o {@code conteudo}; o {@code tamanho} serve ao Content-Length da resposta. */
record ObjetoLido(InputStream conteudo, long tamanho) {
}
