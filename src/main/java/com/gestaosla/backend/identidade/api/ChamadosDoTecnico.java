package com.gestaosla.backend.identidade.api;

import java.util.UUID;

import com.gestaosla.backend.shared.api.Ator;

/**
 * O que a inativação de um técnico precisa saber e fazer com os chamados dele (RN-133, RN-134). O identidade declara a
 * porta e o módulo chamado a implementa, na [chamado - 13]: o chamado depende do identidade, nunca o contrário.
 */
public interface ChamadosDoTecnico {

    /** Quantos chamados do técnico estão em {@code ATRIBUIDO}, {@code EM_ATENDIMENTO} ou {@code RESOLVIDO}. */
    long contarNaoFinalizados(UUID tecnicoSub);

    /** Transfere esses chamados ao substituto na transação de quem chama (RN-134) e os audita em nome do {@code ator}. */
    void transferir(UUID tecnicoSub, UUID substitutoSub, Ator ator);
}
