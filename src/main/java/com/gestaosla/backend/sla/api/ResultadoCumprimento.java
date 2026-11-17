package com.gestaosla.backend.sla.api;

import java.util.Optional;

/**
 * Resultado de {@link RelogiosSla#cumprir}.
 *
 * @param cumpriuAgora {@code false} se o relógio já tinha sido cumprido neste ciclo (o primeiro evento é o que vale)
 * @param violacao     presente quando o cumprimento veio depois do prazo e a violação ainda não tinha sido marcada pela
 *                     rotina de 15 minutos: o chamador grava o aviso
 */
public record ResultadoCumprimento(boolean cumpriuAgora, Optional<Violacao> violacao) {
}
