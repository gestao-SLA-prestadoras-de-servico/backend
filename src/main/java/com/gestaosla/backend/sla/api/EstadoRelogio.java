package com.gestaosla.backend.sla.api;

/**
 * Estado derivado do relógio; não é gravado. Violado continua VIOLADO mesmo se cumprido depois (RN-69). O "apertando"
 * (perto de vencer) é cálculo do frontend.
 */
public enum EstadoRelogio {
    CORRENDO,
    CUMPRIDO,
    VIOLADO
}
