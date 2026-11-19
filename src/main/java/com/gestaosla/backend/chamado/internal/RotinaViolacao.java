package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.sla.api.RelogioVencido;
import com.gestaosla.backend.sla.api.RelogiosSla;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Detecção de violação a cada 15 minutos (RF-34, RN-66): marca os relógios vencidos sem cumprimento, com o horário do
 * estouro (RN-67), e avisa gestores e técnico (RN-39). Chamado RESOLVIDO entra (o relógio de resposta ou de campo pode
 * ainda correr); ENCERRADO e CANCELADO ficam de fora, porque os relógios do cancelado continuam ativos ([chamado - 09]).
 * Lotes com uma transação cada (RN-119). Relógio travado por uma ação do usuário é pulado e volta na passada seguinte.
 * No Prometheus: {@code sla_violacoes_total{relogio}}.
 */
@Component
@RequiredArgsConstructor
class RotinaViolacao {

    static final int TAMANHO_LOTE = 100;

    private final RelogiosSla relogios;
    private final ChamadoRepository chamados;
    private final NotificadorViolacao notificador;
    private final HistoricoChamado historico;
    private final TransactionTemplate transacao;
    private final MeterRegistry metricas;
    private final Clock clock;

    private record Cursor(Instant venceEm, UUID id) {
    }

    @Scheduled(cron = "${app.chamado.rotina-violacao.cron}")
    void executar() {
        executar(TAMANHO_LOTE);
    }

    void executar(int tamanhoLote) {
        Instant agora = Instant.now(clock);
        Cursor cursor = new Cursor(Instant.EPOCH, new UUID(0, 0));
        while (cursor != null) {
            Cursor apos = cursor;
            cursor = transacao.execute(s -> lote(agora, apos, tamanhoLote));
        }
    }

    /** @return o cursor do próximo lote, ou {@code null} se este veio incompleto */
    private Cursor lote(Instant agora, Cursor apos, int tamanhoLote) {
        List<RelogioVencido> vencidos = relogios.travarVencidos(agora, apos.venceEm(), apos.id(), tamanhoLote);
        Map<UUID, Chamado> porId = chamados.findAllById(vencidos.stream().map(RelogioVencido::chamadoId).distinct().toList())
                .stream().collect(Collectors.toMap(Chamado::getId, Function.identity()));
        for (RelogioVencido vencido : vencidos) {
            Chamado chamado = porId.get(vencido.chamadoId());
            if (chamado.getEstado().finalizado()) {
                // ponytail: relido a cada passada; se os vencidos de finalizados crescerem, inativar os relógios no fim.
                continue;
            }
            relogios.marcarViolacao(vencido.id()).ifPresent(violacao -> {
                notificador.notificar(chamado, List.of(violacao));
                historico.registrar(chamado.getId(), TipoEventoHistorico.VIOLACAO_SLA, Ator.SISTEMA, Map.of(
                        "relogio", violacao.tipo().name(), "ciclo", violacao.ciclo(), "violadoEm", violacao.violadaEm().toString()));
                metricas.counter("sla.violacoes", "relogio", violacao.tipo().name()).increment();
            });
        }
        if (vencidos.size() < tamanhoLote) {
            return null;
        }
        RelogioVencido ultimo = vencidos.getLast();
        return new Cursor(ultimo.venceEm(), ultimo.id());
    }
}
