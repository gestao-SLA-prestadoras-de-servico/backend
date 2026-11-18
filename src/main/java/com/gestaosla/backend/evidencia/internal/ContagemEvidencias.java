package com.gestaosla.backend.evidencia.internal;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.chamado.api.EvidenciasDoChamado;

import lombok.RequiredArgsConstructor;

/** Implementa a porta declarada pelo chamado; conta todas as evidências do atendimento, em qualquer estado. */
@Service
@RequiredArgsConstructor
class ContagemEvidencias implements EvidenciasDoChamado {

    private final EvidenciaRepository evidencias;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> contarPorAtendimento(UUID chamadoId) {
        return evidencias.contarPorAtendimento(chamadoId).stream()
                .collect(Collectors.toMap(EvidenciaRepository.Contagem::getAtendimentoId, EvidenciaRepository.Contagem::getTotal));
    }
}
