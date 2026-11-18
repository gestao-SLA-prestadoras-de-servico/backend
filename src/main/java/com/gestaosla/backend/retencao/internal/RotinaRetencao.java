package com.gestaosla.backend.retencao.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gestaosla.backend.outbox.api.LimpezaOutbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class RotinaRetencao {

    private static final Logger log = LoggerFactory.getLogger(RotinaRetencao.class);
    static final int TAMANHO_LOTE = 100;

    private final Clock relogio;
    private final LimpezaOutbox outbox;
    private final MeterRegistry metricas;

    @Scheduled(cron = "${app.retencao.cron:0 0 2 * * *}", zone = "${app.fuso-horario}")
    void executar() {
        Instant agora = relogio.instant();
        executarPasso("outbox", () -> outbox.removerLote(agora.minus(7, ChronoUnit.DAYS), TAMANHO_LOTE));
    }

    void executarPasso(String passo, IntSupplier removerLote) {
        Timer.Sample tempo = Timer.start(metricas);
        long total = 0;
        try {
            var itens = metricas.counter("retencao.itens", "passo", passo);
            int removidos;
            do {
                removidos = removerLote.getAsInt();
                itens.increment(removidos);
                total += removidos;
            } while (removidos == TAMANHO_LOTE);
        } catch (RuntimeException erro) {
            metricas.counter("retencao.falhas", "passo", passo).increment();
            log.warn("Falha no passo de retenção {}: {}", passo, erro.getClass().getSimpleName());
        } finally {
            tempo.stop(metricas.timer("retencao.duracao", "passo", passo));
            log.info("Retenção: passo={}, itens removidos={}", passo, total);
        }
    }
}
