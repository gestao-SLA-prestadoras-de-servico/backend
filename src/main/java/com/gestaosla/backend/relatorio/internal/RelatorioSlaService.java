package com.gestaosla.backend.relatorio.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gestaosla.backend.relatorio.internal.RelatorioSlaRepository.LinhaAgregada;

@Service
class RelatorioSlaService {

    private final RelatorioSlaRepository repository;

    RelatorioSlaService(RelatorioSlaRepository repository) {
        this.repository = repository;
    }

    RelatorioSlaResposta gerar(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        List<LinhaAgregada> linhasGerais = repository.geral(inicio, fim, clienteId, tecnicoSub);
        return new RelatorioSlaResposta(
                linhasGerais.stream().map(this::metricasComTipo).toList(),
                agruparCliente(repository.porCliente(inicio, fim, clienteId, tecnicoSub)),
                agruparTecnico(repository.porTecnico(inicio, fim, clienteId, tecnicoSub)),
                linhasGerais.isEmpty() ? "Nenhuma apuração encontrada para os filtros informados." : null);
    }

    private RelogioMetricas metricasComTipo(LinhaAgregada linha) {
        return new RelogioMetricas(linha.tipo(), metricas(linha));
    }

    private Metricas metricas(LinhaAgregada l) {
        long total = l.cumpridos() + l.violados();
        BigDecimal percentual = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(l.cumpridos()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new Metricas(l.cumpridos(), l.violados(), percentual);
    }

    private List<GrupoCliente> agruparCliente(List<LinhaAgregada> linhas) {
        Map<UUID, List<RelogioMetricas>> grupos = new LinkedHashMap<>();
        linhas.forEach(l -> grupos.computeIfAbsent(l.clienteId(), id -> new java.util.ArrayList<>()).add(metricasComTipo(l)));
        return grupos.entrySet().stream().map(e -> new GrupoCliente(e.getKey(), e.getValue())).toList();
    }

    private List<GrupoTecnico> agruparTecnico(List<LinhaAgregada> linhas) {
        Map<UUID, List<RelogioMetricas>> grupos = new LinkedHashMap<>();
        linhas.forEach(l -> grupos.computeIfAbsent(l.tecnicoSub(), id -> new java.util.ArrayList<>()).add(metricasComTipo(l)));
        return grupos.entrySet().stream().map(e -> new GrupoTecnico(e.getKey(), e.getValue())).toList();
    }

    record RelatorioSlaResposta(List<RelogioMetricas> geral, List<GrupoCliente> porCliente,
            List<GrupoTecnico> porTecnico, String mensagemSemResultados) {
    }

    record RelogioMetricas(String tipo, Metricas metricas) {
    }

    record GrupoCliente(UUID clienteId, List<RelogioMetricas> relogios) {
    }

    record GrupoTecnico(UUID tecnicoSub, List<RelogioMetricas> relogios) {
    }

    record Metricas(long cumpridos, long violados, BigDecimal percentual) {
    }
}
