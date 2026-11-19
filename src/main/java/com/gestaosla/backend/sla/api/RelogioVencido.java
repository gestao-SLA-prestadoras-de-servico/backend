package com.gestaosla.backend.sla.api;

import java.time.Instant;
import java.util.UUID;

/** Relógio correndo com o prazo vencido, devolvido à rotina de violação ({@link RelogiosSla#travarVencidos}). */
public record RelogioVencido(UUID id, UUID chamadoId, Instant venceEm) {
}
