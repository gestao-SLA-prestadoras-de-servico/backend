package com.gestaosla.backend.evidencia.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.ChamadosParaRetencao;
import com.gestaosla.backend.evidencia.api.ExpurgoEvidencias;
import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ExpurgoEvidenciasService implements ExpurgoEvidencias {

    private record Lote(UUID ultimo, int lidos, int removidos) {
    }

    private final EvidenciaRepository evidencias;
    private final ChamadosParaRetencao chamados;
    private final ArmazenamentoEvidencias armazenamento;
    private final AuditoriaService auditoria;
    private final TransactionTemplate transacao;

    @Override
    public void executar(Instant agora, int tamanho, IntConsumer contabilizar) {
        Instant corte = agora.minus(180, ChronoUnit.DAYS);
        Lote lote = new Lote(null, 0, 0);
        do {
            UUID cursor = lote.ultimo();
            lote = transacao.execute(status -> expurgarLote(corte, agora, cursor, tamanho));
            contabilizar.accept(lote.removidos());
        } while (lote.lidos() == tamanho);
    }

    private Lote expurgarLote(Instant corte, Instant agora, UUID cursor, int tamanho) {
        var candidatas = evidencias.candidatasExpurgo(cursor, PageRequest.ofSize(tamanho));
        var finalizados = chamados.finalizadosAntes(candidatas.stream().map(EvidenciaRepository.CandidataExpurgo::getChamadoId)
                .distinct().toList(), corte);
        int removidas = 0;
        for (var candidata : candidatas) {
            if (!finalizados.contains(candidata.getChamadoId())) {
                continue;
            }
            var evidencia = evidencias.buscarComTrava(candidata.getId()).orElseThrow();
            if (evidencia.getEstado() == EstadoEvidencia.EXPURGADA) {
                continue;
            }
            armazenamento.removerPrefixo(new ChaveObjeto(evidencia.getChamadoId(), evidencia.getId()).prefixo());
            evidencia.expurgar(agora);
            auditoria.registrar(Ator.SISTEMA, OperacaoAuditada.EXPURGO_EVIDENCIA, EntidadeAuditada.EVIDENCIA,
                    evidencia.getId(), evidencia.getChamadoId(), Map.of("motivo", "EXPIRACAO_RETENCAO"));
            removidas++;
        }
        UUID ultimo = candidatas.isEmpty() ? cursor : candidatas.getLast().getId();
        return new Lote(ultimo, candidatas.size(), removidas);
    }
}
