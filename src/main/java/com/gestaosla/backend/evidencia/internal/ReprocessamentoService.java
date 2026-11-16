package com.gestaosla.backend.evidencia.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import lombok.RequiredArgsConstructor;

/**
 * Reprocessamento manual da evidência em FALHA (RF-29, RN-121): volta a RECEBIDA, audita e grava um evento novo na
 * outbox, tudo na mesma transação. A mensagem antiga, se estiver na DLQ e for reinjetada à mão, acha a evidência já
 * processada e é descartada (idempotência da evidencia-03).
 */
@Service
@RequiredArgsConstructor
class ReprocessamentoService {

    private final EvidenciaRepository evidencias;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final Clock clock;

    @Transactional
    void reprocessar(UUID id, UsuarioCorrente usuario) {
        Evidencia evidencia = evidencias.findById(id).orElseThrow(RecursoNaoEncontradoException::new);
        // UPDATE condicional: dois gestores ao mesmo tempo, só um muda a linha.
        if (evidencias.reprocessar(id, Instant.now(clock)) == 0) {
            throw new RegraNegocioException(CodigoErro.REPROCESSAMENTO_NAO_PERMITIDO,
                    "Só a evidência em FALHA pode ser reprocessada.");
        }
        auditoria.registrar(Ator.usuario(usuario.sub(), usuario.papel()), OperacaoAuditada.REPROCESSAMENTO,
                EntidadeAuditada.EVIDENCIA, id, evidencia.getChamadoId(), Map.of("de", "FALHA", "para", "RECEBIDA"));
        outbox.registrar(RecepcaoEvidenciaService.eventoProcessamento(id, evidencia.getChamadoId(), evidencia.getChaveOriginal()));
    }
}
