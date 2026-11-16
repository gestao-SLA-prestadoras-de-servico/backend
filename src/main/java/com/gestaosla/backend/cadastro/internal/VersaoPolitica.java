package com.gestaosla.backend.cadastro.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Versão de uma política (RN-04): nunca é alterada. {@code @Immutable} faz o Hibernate ignorar mudanças, e um trigger
 * no banco rejeita UPDATE e DELETE. Sem {@code EntidadeBase}: registro imutável não tem updated_at.
 */
@Entity
@Immutable
@Table(name = "politica_sla_versao")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class VersaoPolitica {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID politicaId;

    private int numero;

    @Embedded
    @AttributeOverride(name = "resposta", column = @Column(name = "resposta_alta"))
    @AttributeOverride(name = "campo", column = @Column(name = "campo_alta"))
    @AttributeOverride(name = "resolucao", column = @Column(name = "resolucao_alta"))
    private Prazos alta;

    @Embedded
    @AttributeOverride(name = "resposta", column = @Column(name = "resposta_normal"))
    @AttributeOverride(name = "campo", column = @Column(name = "campo_normal"))
    @AttributeOverride(name = "resolucao", column = @Column(name = "resolucao_normal"))
    private Prazos normal;

    @Enumerated(EnumType.STRING)
    private Prioridade prioridadePadrao;

    private Instant criadaEm;

    private UUID criadaPor;

    VersaoPolitica(UUID politicaId, int numero, DadosVersao dados, Instant criadaEm, UUID criadaPor) {
        this.politicaId = politicaId;
        this.numero = numero;
        this.alta = dados.prazos().alta();
        this.normal = dados.prazos().normal();
        this.prioridadePadrao = dados.prioridadePadrao();
        this.criadaEm = criadaEm;
        this.criadaPor = criadaPor;
    }

    PrazosPorPrioridade prazos() {
        return new PrazosPorPrioridade(alta, normal);
    }
}
