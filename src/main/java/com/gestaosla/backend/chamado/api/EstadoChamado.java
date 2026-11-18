package com.gestaosla.backend.chamado.api;

import java.util.Set;

/**
 * Estados do chamado (RN-09) e a máquina de transições (RN-10). Não existe REABERTO: a reabertura é RESOLVIDO →
 * ATRIBUIDO (RN-12). Gravado como texto.
 */
public enum EstadoChamado {
    ABERTO,
    ATRIBUIDO,
    EM_ATENDIMENTO,
    RESOLVIDO,
    ENCERRADO,
    CANCELADO;

    /** Destinos permitidos a partir deste estado (RN-10). ENCERRADO e CANCELADO são finais (RN-11). */
    public Set<EstadoChamado> destinos() {
        return switch (this) {
            case ABERTO -> Set.of(ATRIBUIDO, CANCELADO);
            // EM_ATENDIMENTO → ATRIBUIDO é a reatribuição; RESOLVIDO → ATRIBUIDO, a reabertura.
            case ATRIBUIDO -> Set.of(EM_ATENDIMENTO, CANCELADO);
            case EM_ATENDIMENTO -> Set.of(RESOLVIDO, ATRIBUIDO, CANCELADO);
            case RESOLVIDO -> Set.of(ENCERRADO, ATRIBUIDO);
            case ENCERRADO, CANCELADO -> Set.of();
        };
    }

    public boolean podeIrPara(EstadoChamado destino) {
        return destinos().contains(destino);
    }

    public boolean finalizado() {
        return this == ENCERRADO || this == CANCELADO;
    }
}
