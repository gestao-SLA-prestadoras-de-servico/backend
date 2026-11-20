package com.gestaosla.backend.evidencia.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Evidência de um atendimento (RN-71). Não estende a {@code EntidadeBase}: o id é gerado antes, para compor a chave do
 * objeto que se grava no storage antes da transação (RN-112), e o gerador da base sobrescreveria um id já atribuído. O
 * resto segue a convenção (UUID v7, {@code created_at}, {@code updated_at}).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Evidencia {

    @Id
    private UUID id;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private UUID atendimentoId;

    private UUID chamadoId;

    private String nome;

    private String tipoConteudo;

    private long tamanho;

    @Column(updatable = false)
    private String hashSha256;

    @Column(updatable = false)
    private String chaveOriginal;

    private String chaveMiniatura;

    private String chaveVisualizacao;

    @Enumerated(EnumType.STRING)
    private EstadoEvidencia estado;

    private Instant enviadaEm;

    private Instant expurgadaEm;

    // Wrapper de propósito: nulo significa "nova" para o save() (persist, sem o select do merge), já que o id vem preenchido.
    @Version
    private Long version;

    Evidencia(UUID id, UUID atendimentoId, UUID chamadoId, String nome, String tipoConteudo, long tamanho, String hashSha256,
            String chaveOriginal, Instant enviadaEm) {
        this.id = id;
        this.atendimentoId = atendimentoId;
        this.chamadoId = chamadoId;
        this.nome = nome;
        this.tipoConteudo = tipoConteudo;
        this.tamanho = tamanho;
        this.hashSha256 = hashSha256;
        this.chaveOriginal = chaveOriginal;
        this.estado = EstadoEvidencia.RECEBIDA;
        this.enviadaEm = enviadaEm;
    }

    void expurgar(Instant agora) {
        estado = EstadoEvidencia.EXPURGADA;
        expurgadaEm = agora;
    }
}
