package com.gestaosla.backend.evidencia.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/** Evidências por estado (RF-64), contadas no banco a cada coleta. No Prometheus: {@code evidencias{estado="FALHA"}} etc. */
@Component
@RequiredArgsConstructor
class MetricasEvidencias implements MeterBinder {

    private final JdbcClient jdbc;

    @Override
    public void bindTo(MeterRegistry registro) {
        for (EstadoEvidencia estado : EstadoEvidencia.values()) {
            Gauge.builder("evidencias",
                    () -> jdbc.sql("select count(*) from evidencia where estado = ?").param(estado.name()).query(Long.class).single())
                    .tag("estado", estado.name())
                    .description("Evidências no estado indicado")
                    .register(registro);
        }
    }
}
