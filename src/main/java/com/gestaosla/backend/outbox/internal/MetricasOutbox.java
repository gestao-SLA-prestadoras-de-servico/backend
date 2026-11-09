package com.gestaosla.backend.outbox.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/**
 * Métricas da tabela de saída (RF-64), consultadas no banco a cada coleta (pelo índice parcial dos pendentes): ficam
 * corretas mesmo se o publicador travar. No Prometheus: {@code outbox_eventos_pendentes} e
 * {@code outbox_pendente_mais_antigo_idade_seconds}.
 */
@Component
@RequiredArgsConstructor
class MetricasOutbox implements MeterBinder {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registro) {
        Gauge.builder("outbox.eventos.pendentes",
                () -> jdbc.sql("select count(*) from outbox_eventos where publicado_em is null")
                        .query(Long.class).single())
                .description("Eventos da tabela de saída ainda não publicados")
                .register(registro);
        Gauge.builder("outbox.pendente.mais.antigo.idade",
                () -> jdbc.sql("""
                        select coalesce(extract(epoch from (? - min(criado_em))), 0)
                        from outbox_eventos where publicado_em is null""")
                        .param(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                        .query(Double.class).single())
                .description("Idade do evento pendente mais antigo; 0 sem pendentes")
                .baseUnit("seconds")
                .register(registro);
    }
}
