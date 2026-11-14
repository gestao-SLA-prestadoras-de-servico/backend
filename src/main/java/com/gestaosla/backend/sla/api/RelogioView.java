package com.gestaosla.backend.sla.api;

import java.time.Instant;

/** Relógio como o chamador e o frontend o veem, com o estado derivado. */
public record RelogioView(TipoRelogio tipo, int ciclo, boolean ativo, Instant marcoInicial, int prazoMinutos, Instant venceEm,
        Instant cumpridoEm, Instant violadoEm, EstadoRelogio estado) {
}
