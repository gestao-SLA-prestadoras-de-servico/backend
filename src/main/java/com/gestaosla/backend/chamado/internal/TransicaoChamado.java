package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

/**
 * Único caminho para mudar o estado do chamado: a entidade valida a transição, e aqui a auditoria (RN-121) e o
 * histórico são gravados na mesma transação.
 */
@Component
@RequiredArgsConstructor
class TransicaoChamado {

    private final AuditoriaService auditoria;
    private final HistoricoChamado historico;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    void transicionar(Chamado chamado, EstadoChamado destino, Ator ator) {
        EstadoChamado anterior = chamado.transicionar(destino, Instant.now(clock));
        Map<String, Object> dados = Map.of("de", anterior.name(), "para", destino.name());
        auditoria.registrar(ator, OperacaoAuditada.MUDANCA_ESTADO, EntidadeAuditada.CHAMADO, chamado.getId(), chamado.getId(), dados);
        historico.registrar(chamado.getId(), TipoEventoHistorico.MUDANCA_ESTADO, ator, dados);
    }
}
