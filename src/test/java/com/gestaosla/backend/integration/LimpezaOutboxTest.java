package com.gestaosla.backend.integration;

import static com.gestaosla.backend.retencao.internal.RotinaRetencaoTestHelper.executar;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class LimpezaOutboxTest {

    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RelogioMutavel relogio;
    @Autowired
    private MeterRegistry metricas;

    @Test
    void shouldRemovePublishedEventsAcrossBatchesOnlyAfterSevenDays() {
        Instant publicacao = Instant.parse("2000-01-01T12:00:00Z");
        Instant limite = publicacao.plus(7, ChronoUnit.DAYS);
        var data = publicacao.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into outbox_eventos (id, evento_id, tipo, exchange, routing_key, payload, criado_em, publicado_em)
                select gen_random_uuid(), gen_random_uuid(), 'TESTE_RETENCAO', 'teste', 'teste', '{}', ?, ?
                from generate_series(1, 101)""", data, data);
        UUID pendente = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_eventos (id, evento_id, tipo, exchange, routing_key, payload, criado_em)
                values (?, ?, 'TESTE_RETENCAO', 'teste', 'teste', '{}', ?)""", pendente, pendente, data.minusYears(1));
        var itens = metricas.counter("retencao.itens", "passo", "outbox");
        double antes = itens.count();

        relogio.definir(limite);
        executar(contexto);
        assertThat(contarPublicados()).isEqualTo(101);

        relogio.definir(limite.plusSeconds(1));
        executar(contexto);
        executar(contexto);

        assertThat(contarPublicados()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where evento_id = ?", Integer.class, pendente))
                .isEqualTo(1);
        assertThat(itens.count() - antes).isEqualTo(101);
        assertThat(metricas.get("retencao.duracao").tag("passo", "outbox").timer().count()).isGreaterThanOrEqualTo(3);
        jdbc.update("delete from outbox_eventos where evento_id = ?", pendente);
    }

    private int contarPublicados() {
        return jdbc.queryForObject("""
                select count(*) from outbox_eventos where tipo = 'TESTE_RETENCAO' and publicado_em is not null""",
                Integer.class);
    }
}
