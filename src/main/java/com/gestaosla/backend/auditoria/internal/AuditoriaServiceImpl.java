package com.gestaosla.backend.auditoria.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.auditoria.internal.RegistroAuditoria.TipoAtor;
import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class AuditoriaServiceImpl implements AuditoriaService {

    private final RegistroAuditoriaRepository registros;
    private final Clock clock;

    // MANDATORY: nunca abre transação própria; sem a de quem chama, falha (RN-120).
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Ator ator, OperacaoAuditada operacao, EntidadeAuditada entidadeTipo, UUID entidadeId,
            UUID chamadoId, Map<String, Object> dados) {
        Objects.requireNonNull(ator);
        registros.save(new RegistroAuditoria(Instant.now(clock), ator.sistema() ? TipoAtor.SISTEMA : TipoAtor.USUARIO,
                ator.sub(), ator.papel(), Objects.requireNonNull(operacao), Objects.requireNonNull(entidadeTipo),
                Objects.requireNonNull(entidadeId), chamadoId, dados == null ? Map.of() : dados));
    }
}
