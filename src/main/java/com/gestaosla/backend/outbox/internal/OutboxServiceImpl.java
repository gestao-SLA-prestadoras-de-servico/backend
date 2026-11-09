package com.gestaosla.backend.outbox.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.EventoSaida;
import com.gestaosla.backend.outbox.api.OutboxService;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
class OutboxServiceImpl implements OutboxService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {
    };

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(EventoNotificacao evento) {
        registrar(evento, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(EventoNotificacao evento, UUID eventoId) {
        registrar(new EventoSaida(eventoId, evento.tipo().name(), "notificacoes",
                List.of(evento.tipo().canal().routingKey()), json.convertValue(evento, MAPA)));
    }

    // MANDATORY: nunca abre transação própria; sem a de quem chama, falha (RN-113).
    // JDBC em vez de entidade JPA: o ON CONFLICT DO NOTHING com Hibernate exigiria @SQLInsert sem checar linhas.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(EventoSaida evento) {
        UUID eventoId = evento.eventoId() != null ? evento.eventoId() : UUID.randomUUID();
        OffsetDateTime criadoEm = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        boolean variosCanais = evento.routingKeys().size() > 1;
        for (String routingKey : evento.routingKeys()) {
            // Um id por canal: o ms-notificacoes descarta repetições pelo eventoId (RN-55), então dois canais com o
            // mesmo id perderiam o segundo. Derivado, e não aleatório, para manter determinístico o id da RN-54.
            UUID idDoCanal = variosCanais
                    ? UUID.nameUUIDFromBytes((eventoId + ":" + routingKey).getBytes(StandardCharsets.UTF_8))
                    : eventoId;
            Map<String, Object> payload = new LinkedHashMap<>(evento.payload());
            payload.put("eventoId", idDoCanal);
            jdbc.sql("""
                    insert into outbox_eventos (id, evento_id, tipo, exchange, routing_key, payload, criado_em)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict (evento_id) do nothing""")
                    .params(UUID.randomUUID(), idDoCanal, evento.tipo(), evento.exchange(), routingKey,
                            json.writeValueAsString(payload), criadoEm)
                    .update();
        }
    }
}
