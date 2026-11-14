package com.gestaosla.backend.sla.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Violação recém-marcada, devolvida a quem chamou o sla. É o chamador (módulo chamado) que grava o evento na outbox: o
 * sla não depende do chamado. O {@code eventoId} do aviso é determinístico, composto por chamado, tipo e marco inicial
 * (RN-54), de modo que a violação de um ciclo novo gera um aviso novo.
 *
 * @param violadaEm horário real do estouro, igual ao vencimento (RN-67)
 */
public record Violacao(UUID chamadoId, TipoRelogio tipo, int ciclo, Instant marcoInicial, Instant violadaEm) {
}
