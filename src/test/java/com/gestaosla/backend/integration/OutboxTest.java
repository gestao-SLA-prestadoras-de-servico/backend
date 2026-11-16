package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.outbox.api.EventoSaida;
import com.gestaosla.backend.outbox.api.OutboxService;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class OutboxTest {

    @Autowired
    private OutboxService outbox;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Test
    void shouldFailOutsideTransaction() {
        assertThatThrownBy(() -> outbox.registrar(evento(UUID.randomUUID(), "interna")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void shouldStorePendingEventWithinCallerTransaction() {
        Instant agora = Instant.parse("2026-11-09T12:00:00Z");
        relogio.definir(agora);
        UUID eventoId = UUID.randomUUID();

        transacao.executeWithoutResult(t -> outbox.registrar(evento(eventoId, "interna")));

        Map<String, Object> linha = jdbc.queryForMap("""
                select tipo, exchange, routing_key, criado_em, publicado_em, tentativas, ultimo_erro,
                       payload ->> 'eventoId' as payload_evento_id, payload ->> 'titulo' as titulo
                from outbox_eventos where evento_id = ?""", eventoId);
        assertThat(((java.sql.Timestamp) linha.get("criado_em")).toInstant()).isEqualTo(agora);
        assertThat(linha).containsEntry("tipo", "ATRIBUICAO").containsEntry("exchange", "notificacoes")
                .containsEntry("routing_key", "interna").containsEntry("publicado_em", null)
                .containsEntry("tentativas", 0).containsEntry("ultimo_erro", null)
                .containsEntry("payload_evento_id", eventoId.toString()).containsEntry("titulo", "Impressora parada");
    }

    @Test
    void shouldLeaveNoEventWhenBusinessOperationRollsBack() {
        UUID eventoId = UUID.randomUUID();

        transacao.executeWithoutResult(t -> {
            outbox.registrar(evento(eventoId, "interna"));
            t.setRollbackOnly();
        });

        assertThat(contar(eventoId)).isZero();
    }

    @Test
    void shouldIgnoreExistingEventIdWithoutErrorOrDuplicate() {
        UUID eventoId = UUID.randomUUID();
        transacao.executeWithoutResult(t -> outbox.registrar(evento(eventoId, "interna")));

        transacao.executeWithoutResult(t -> outbox.registrar(new EventoSaida(eventoId, "ATRIBUICAO", "notificacoes",
                List.of("interna"), Map.of("titulo", "Outro título"))));

        assertThat(contar(eventoId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload ->> 'titulo' from outbox_eventos where evento_id = ?",
                String.class, eventoId)).isEqualTo("Impressora parada");
    }

    @Test
    void shouldStoreOneRecordPerChannelWithDistinctDeterministicIds() {
        UUID eventoId = UUID.randomUUID();
        EventoSaida doisCanais = new EventoSaida(eventoId, "ATRIBUICAO", "notificacoes",
                List.of("email", "interna"), Map.of("titulo", "Impressora parada"));

        transacao.executeWithoutResult(t -> outbox.registrar(doisCanais));
        // Repetir o mesmo evento (ex.: violação detectada de novo, RN-54) não cria novas linhas.
        transacao.executeWithoutResult(t -> outbox.registrar(doisCanais));

        assertThat(contar(eventoId)).isZero();
        List<Map<String, Object>> doEvento = jdbc.queryForList("""
                select evento_id, routing_key, payload ->> 'eventoId' as payload_evento_id from outbox_eventos
                where evento_id in (?, ?) order by routing_key""",
                derivado(eventoId, "email"), derivado(eventoId, "interna"));
        assertThat(doEvento).extracting(l -> l.get("routing_key"))
                .containsExactly("email", "interna");
        assertThat(doEvento)
                .allSatisfy(l -> assertThat(l.get("payload_evento_id")).isEqualTo(l.get("evento_id").toString()));
        assertThat(doEvento.get(0).get("evento_id")).isNotEqualTo(doEvento.get(1).get("evento_id"));
    }

    private static UUID derivado(UUID eventoId, String routingKey) {
        return UUID.nameUUIDFromBytes((eventoId + ":" + routingKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static EventoSaida evento(UUID eventoId, String routingKey) {
        return new EventoSaida(eventoId, "ATRIBUICAO", "notificacoes", List.of(routingKey),
                Map.of("titulo", "Impressora parada"));
    }

    private int contar(UUID eventoId) {
        return jdbc.queryForObject("select count(*) from outbox_eventos where evento_id = ?", Integer.class, eventoId);
    }
}
