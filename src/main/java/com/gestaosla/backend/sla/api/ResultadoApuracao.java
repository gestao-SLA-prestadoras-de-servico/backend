package com.gestaosla.backend.sla.api;

/**
 * Resultado congelado de um relógio. Relógio que chegou ao encerramento sem o evento e ainda dentro do prazo conta como
 * CUMPRIDO; com o prazo vencido, VIOLADO.
 */
public enum ResultadoApuracao {
    CUMPRIDO,
    VIOLADO
}
