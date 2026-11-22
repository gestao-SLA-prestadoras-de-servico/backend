package com.gestaosla.backend.relatorio.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationContext;

/**
 * Aciona o relatório interno a partir de {@code integration/} (o CI só roda testes em {@code unit/} e
 * {@code integration/}).
 */
public final class RelatorioSlaTestHelper {

    private final RelatorioSlaService service;

    public RelatorioSlaTestHelper(ApplicationContext contexto) {
        this.service = contexto.getBean(RelatorioSlaService.class);
    }

    public Resposta gerar(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        var resposta = service.gerar(inicio, fim, clienteId, tecnicoSub);
        return new Resposta(
                resposta.geral().stream().map(RelatorioSlaTestHelper::relogio).toList(),
                resposta.porCliente().stream().map(grupo -> new GrupoCliente(grupo.clienteId(),
                        grupo.relogios().stream().map(RelatorioSlaTestHelper::relogio).toList())).toList(),
                resposta.porTecnico().stream().map(grupo -> new GrupoTecnico(grupo.tecnicoSub(),
                        grupo.relogios().stream().map(RelatorioSlaTestHelper::relogio).toList())).toList(),
                resposta.mensagemSemResultados());
    }

    private static RelogioMetricas relogio(RelatorioSlaService.RelogioMetricas relogio) {
        return new RelogioMetricas(relogio.tipo(), new Metricas(relogio.metricas().cumpridos(),
                relogio.metricas().violados(), relogio.metricas().percentual()));
    }

    public record Resposta(List<RelogioMetricas> geral, List<GrupoCliente> porCliente,
            List<GrupoTecnico> porTecnico, String mensagemSemResultados) {
    }

    public record RelogioMetricas(String tipo, Metricas metricas) {
    }

    public record GrupoCliente(UUID clienteId, List<RelogioMetricas> relogios) {
    }

    public record GrupoTecnico(UUID tecnicoSub, List<RelogioMetricas> relogios) {
    }

    public record Metricas(long cumpridos, long violados, BigDecimal percentual) {
    }
}
