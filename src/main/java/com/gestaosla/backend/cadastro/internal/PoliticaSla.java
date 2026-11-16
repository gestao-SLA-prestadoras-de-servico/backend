package com.gestaosla.backend.cadastro.internal;

import com.gestaosla.backend.shared.api.EntidadeBase;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Política de SLA (RF-07). Os prazos ficam nas versões ({@link VersaoPolitica}); o nome não muda. */
@Entity
@Table(name = "politica_sla")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PoliticaSla extends EntidadeBase {

    private String nome;

    PoliticaSla(String nome) {
        this.nome = nome;
    }
}
