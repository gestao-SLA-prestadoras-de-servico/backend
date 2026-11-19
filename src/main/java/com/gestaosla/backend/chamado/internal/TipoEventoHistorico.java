package com.gestaosla.backend.chamado.internal;

/**
 * Eventos da linha do tempo do chamado e quem os vê. O gestor vê todos. Gravado como texto: valor publicado não muda
 * de nome. Cada task acrescenta os seus eventos.
 */
enum TipoEventoHistorico {

    ABERTURA(true, true),
    MUDANCA_ESTADO(true, true),
    // Decisão da [chamado - 05]: a alteração da prioridade é interna, só do gestor.
    ALTERACAO_PRIORIDADE(false, false),
    // [chamado - 06]: o cliente recebe o posicionamento por e-mail; vê também na linha do tempo.
    POSICIONAMENTO(true, true),
    // [chamado - 07] e transferência por inativação: troca de técnico é organização interna.
    ATRIBUICAO(false, true),
    REATRIBUICAO(false, true),
    TRANSFERENCIA_POR_INATIVACAO(false, true),
    // [chamado - 12]: o cliente reabre com justificativa; os três perfis veem.
    REABERTURA(true, true),
    // [chamado - 14]: gravado pela rotina, ator SISTEMA; o SLA já é visível aos três perfis.
    VIOLACAO_SLA(true, true);

    private final boolean visivelCliente;
    private final boolean visivelTecnico;

    TipoEventoHistorico(boolean visivelCliente, boolean visivelTecnico) {
        this.visivelCliente = visivelCliente;
        this.visivelTecnico = visivelTecnico;
    }

    boolean visivelCliente() {
        return visivelCliente;
    }

    boolean visivelTecnico() {
        return visivelTecnico;
    }
}
