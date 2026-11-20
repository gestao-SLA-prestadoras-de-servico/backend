package com.gestaosla.backend.retencao.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gestaosla.backend.auditoria.api.ExpurgoAuditoria;
import com.gestaosla.backend.evidencia.api.ExpurgoEvidencias;
import com.gestaosla.backend.evidencia.api.LimpezaOrfaos;
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
    private final ExpurgoAuditoria auditoria;
    private final ExpurgoEvidencias evidencias;
    private final LimpezaOrfaos orfaos;
    private final MeterRegistry metricas;

    @Scheduled(cron = "${app.retencao.cron:0 0 2 * * *}", zone = "${app.fuso-horario}")
    void executar() {
        Instant agora = relogio.instant();
        executarPasso("outbox", () -> outbox.removerLote(agora.minus(7, ChronoUnit.DAYS), TAMANHO_LOTE));
        executarPasso("auditoria", () -> auditoria.removerLote(agora, TAMANHO_LOTE));
        executarPasso("evidencias", contar -> evidencias.executar(agora, TAMANHO_LOTE, contar));
        executarPasso("orfaos", contar -> orfaos.executar(agora, TAMANHO_LOTE, contar));
    }

    void executarPasso(String passo, IntSupplier removerLote) {
        executarPasso(passo, contabilizar -> {
            int removidos;
            do {
                removidos = removerLote.getAsInt();
                contabilizar.accept(removidos);
            } while (removidos == TAMANHO_LOTE);
        });
    }

    private void executarPasso(String passo, Consumer<IntConsumer> operacao) {
        Timer.Sample tempo = Timer.start(metricas);
        var itens = metricas.counter("retencao.itens", "passo", passo);
        var total = new AtomicLong();
        try {
            operacao.accept(removidos -> {
                itens.increment(removidos);
                total.addAndGet(removidos);
            });
        } catch (RuntimeException erro) {
            metricas.counter("retencao.falhas", "passo", passo).increment();
            log.warn("Falha no passo de retenção {}: {}", passo, erro.getClass().getSimpleName());
        } finally {
            tempo.stop(metricas.timer("retencao.duracao", "passo", passo));
            log.info("Retenção: passo={}, itens removidos={}", passo, total.get());
        }
    }
}
