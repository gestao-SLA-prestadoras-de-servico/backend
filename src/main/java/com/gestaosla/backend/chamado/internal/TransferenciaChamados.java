package com.gestaosla.backend.chamado.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

/**
 * Porta do identidade para a inativação do técnico (RN-133 a RN-135) e a carga de {@code GET /tecnicos}. Classe
 * própria, separada da atribuição: os testes do identidade substituem a porta sem derrubar o resto do chamado.
 */
@Service
@RequiredArgsConstructor
class TransferenciaChamados implements ChamadosDoTecnico {

    private static final List<EstadoChamado> NAO_FINALIZADOS = List.of(EstadoChamado.ATRIBUIDO, EstadoChamado.EM_ATENDIMENTO,
            EstadoChamado.RESOLVIDO);

    private final ChamadoRepository chamados;
    private final AtribuicaoService atribuicao;
    private final AuditoriaService auditoria;
    private final HistoricoChamado historico;

    @Override
    @Transactional(readOnly = true)
    public long contarNaoFinalizados(UUID tecnicoSub) {
        return chamados.countByTecnicoSubAndEstadoIn(tecnicoSub, NAO_FINALIZADOS);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> contarNaoFinalizadosPorEstado(UUID tecnicoSub) {
        Map<String, Long> contagem = new LinkedHashMap<>();
        // ponytail: uma contagem por estado (3 consultas); só o modal da inativação usa.
        NAO_FINALIZADOS.forEach(estado -> contagem.put(estado.name(),
                chamados.countByTecnicoSubAndEstadoIn(tecnicoSub, List.of(estado))));
        return contagem;
    }

    /**
     * RN-134: ATRIBUIDO passa ao substituto; EM_ATENDIMENTO volta a ATRIBUIDO com o substituto e o atendimento
     * interrompido; RESOLVIDO mantém o estado com o substituto. É a única troca permitida em RESOLVIDO (RN-20). O
     * substituto é avisado de cada chamado; o inativado não recebe aviso de remoção (RN-135).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferir(UUID tecnicoSub, UUID substitutoSub, Ator ator) {
        for (Chamado chamado : chamados.findByTecnicoSubAndEstadoIn(tecnicoSub, NAO_FINALIZADOS)) {
            atribuicao.trocarTecnico(chamado, substitutoSub, ator);
            Map<String, Object> dados = Map.of("anterior", tecnicoSub.toString(), "novo", substitutoSub.toString());
            auditoria.registrar(ator, OperacaoAuditada.TRANSFERENCIA_POR_INATIVACAO, EntidadeAuditada.CHAMADO, chamado.getId(),
                    chamado.getId(), dados);
            historico.registrar(chamado.getId(), TipoEventoHistorico.TRANSFERENCIA_POR_INATIVACAO, ator, dados);
        }
    }
}
