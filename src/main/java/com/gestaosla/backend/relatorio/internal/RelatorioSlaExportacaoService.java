package com.gestaosla.backend.relatorio.internal;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gestaosla.backend.cadastro.api.RazaoSocialClienteConsulta;
import com.gestaosla.backend.identidade.api.NomeTecnicoConsulta;

@Service
class RelatorioSlaExportacaoService {

    private final RelatorioSlaService relatorio;
    private final RazaoSocialClienteConsulta clientes;
    private final NomeTecnicoConsulta tecnicos;

    RelatorioSlaExportacaoService(RelatorioSlaService relatorio, RazaoSocialClienteConsulta clientes,
            NomeTecnicoConsulta tecnicos) {
        this.relatorio = relatorio;
        this.clientes = clientes;
        this.tecnicos = tecnicos;
    }

    RelatorioSlaDadosExportacao gerar(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        var dados = relatorio.gerar(inicio, fim, clienteId, tecnicoSub);
        Set<UUID> clienteIds = new LinkedHashSet<>();
        Set<UUID> subsTecnicos = new LinkedHashSet<>();
        dados.porCliente().forEach(g -> clienteIds.add(g.clienteId()));
        dados.porTecnico().forEach(g -> subsTecnicos.add(g.tecnicoSub()));
        if (clienteId != null) clienteIds.add(clienteId);
        if (tecnicoSub != null) subsTecnicos.add(tecnicoSub);
        return new RelatorioSlaDadosExportacao(dados, clientes.razoesSociais(clienteIds),
                tecnicos.nomesTecnicos(subsTecnicos), clienteId, tecnicoSub);
    }
}
