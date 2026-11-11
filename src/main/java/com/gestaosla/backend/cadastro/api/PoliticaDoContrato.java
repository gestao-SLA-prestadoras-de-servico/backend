package com.gestaosla.backend.cadastro.api;

import java.util.UUID;

/** Versão da política vigente de um contrato, com os prazos em minutos de janela (RN-29). */
public record PoliticaDoContrato(UUID politicaId, UUID versaoId, int numero, Prioridade prioridadePadrao, Prazos alta,
        Prazos normal) {

    public Prazos prazos(Prioridade prioridade) {
        return prioridade == Prioridade.ALTA ? alta : normal;
    }
}
