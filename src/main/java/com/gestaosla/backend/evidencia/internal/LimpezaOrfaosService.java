package com.gestaosla.backend.evidencia.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.evidencia.api.LimpezaOrfaos;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class LimpezaOrfaosService implements LimpezaOrfaos {

    private final ArmazenamentoEvidencias armazenamento;
    private final EvidenciaRepository evidencias;
    private final TransactionTemplate transacao;

    @Override
    public void executar(Instant agora, int tamanho, IntConsumer contabilizar) {
        Instant corte = agora.minus(24, ChronoUnit.HOURS);
        String cursor = null;
        do {
            var pagina = armazenamento.listarPagina(cursor, tamanho);
            int removidos = transacao.execute(status -> limparPagina(pagina, corte));
            contabilizar.accept(removidos);
            cursor = pagina.proximoCursor();
        } while (cursor != null);
    }

    private int limparPagina(ArmazenamentoEvidencias.Pagina pagina, Instant corte) {
        // A trava mantém a classificação válida até o delete, inclusive se um gestor pedir reprocessamento.
        Map<UUID, Optional<Evidencia>> registros = new HashMap<>();
        var orfaos = pagina.objetos().stream()
                .filter(objeto -> objeto.ultimaModificacao().isBefore(corte))
                .filter(objeto -> orfao(objeto.chave(), registros))
                .map(ObjetoArmazenado::chave).toList();
        armazenamento.removerObjetos(orfaos);
        return orfaos.size();
    }

    private boolean orfao(String chave, Map<UUID, Optional<Evidencia>> registros) {
        return ChaveObjeto.identificar(chave)
                .flatMap(referencia -> registros.computeIfAbsent(referencia.evidenciaId(), evidencias::buscarComTrava)
                        .filter(evidencia -> evidencia.getChamadoId().equals(referencia.chamadoId())))
                .map(evidencia -> switch (evidencia.getEstado()) {
                    case RECEBIDA, PROCESSANDO -> false;
                    case EXPURGADA -> true;
                    case DISPONIVEL, FALHA -> !chave.equals(evidencia.getChaveOriginal())
                            && !Objects.equals(chave, evidencia.getChaveMiniatura())
                            && !Objects.equals(chave, evidencia.getChaveVisualizacao());
                }).orElse(true);
    }
}
