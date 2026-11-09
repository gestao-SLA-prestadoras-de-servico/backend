package com.gestaosla.backend.outbox.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento destinado à mensageria (RF-49).
 *
 * @param eventoId    {@code null} para gerar um novo; informado quando precisa ser determinístico (violação de SLA,
 *                    RN-54)
 * @param routingKeys um ou mais canais; cada um vira um registro na tabela de saída
 * @param payload     conteúdo da mensagem (RN-53); o {@code eventoId} de cada registro é acrescentado na gravação
 */
public record EventoSaida(UUID eventoId, String tipo, String exchange, List<String> routingKeys,
        Map<String, Object> payload) {

    public EventoSaida {
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(payload);
        routingKeys = List.copyOf(routingKeys);
        if (routingKeys.isEmpty()) {
            throw new IllegalArgumentException("O evento precisa de ao menos uma routing key.");
        }
    }
}
