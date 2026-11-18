package com.gestaosla.backend.chamado.api;

import java.util.UUID;

/** Atendimento PENDENTE ou EM_ANDAMENTO de um chamado: é a ele que uma evidência nova pertence (RN-71). */
public record AtendimentoCorrente(UUID id, UUID chamadoId, UUID tecnicoSub, EstadoAtendimento estado) {
}
