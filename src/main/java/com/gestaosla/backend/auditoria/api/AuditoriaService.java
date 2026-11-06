package com.gestaosla.backend.auditoria.api;

import java.util.Map;
import java.util.UUID;

import com.gestaosla.backend.shared.api.Ator;

/** Trilha de auditoria imutável (RF-41, RN-120 a RN-124). */
public interface AuditoriaService {

    /**
     * Registra a operação na transação de quem chama (RN-120): se ela der rollback, o registro some junto. Chamado fora
     * de uma transação, lança {@link org.springframework.transaction.IllegalTransactionStateException}. A hora vem do
     * Clock injetado (RN-62).
     *
     * @param chamadoId chamado relacionado, ou {@code null} quando a operação não envolve chamado
     * @param dados     detalhes da operação (ex.: estado de/para, justificativa); gravados em jsonb
     */
    void registrar(Ator ator, OperacaoAuditada operacao, EntidadeAuditada entidadeTipo, UUID entidadeId, UUID chamadoId,
            Map<String, Object> dados);
}
