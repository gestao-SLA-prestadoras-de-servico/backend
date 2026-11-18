package com.gestaosla.backend.chamado.api;

/** Estados do atendimento. PENDENTE e EM_ANDAMENTO são correntes: no máximo um por chamado. Gravado como texto. */
public enum EstadoAtendimento {
    PENDENTE,
    EM_ANDAMENTO,
    CONCLUIDO,
    INTERROMPIDO;

    public boolean corrente() {
        return this == PENDENTE || this == EM_ANDAMENTO;
    }
}
