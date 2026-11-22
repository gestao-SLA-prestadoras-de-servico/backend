package com.gestaosla.backend.relatorio.internal;

import java.util.Map;
import java.util.UUID;

import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.RelatorioSlaResposta;

record RelatorioSlaDadosExportacao(RelatorioSlaResposta relatorio, Map<UUID, String> razoesSociais,
        Map<UUID, String> nomesTecnicos, UUID clienteFiltro, UUID tecnicoFiltro) {
}
