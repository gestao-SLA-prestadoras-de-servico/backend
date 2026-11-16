package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Preferência ativa de um gestor num chamado (RN-40). Sem {@code EntidadeBase}: a linha nunca é alterada, só criada e
 * removida, então não tem updated_at.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PreferenciaNotificacao {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private UUID chamadoId;

    private UUID gestorSub;

    PreferenciaNotificacao(UUID chamadoId, UUID gestorSub) {
        this.chamadoId = chamadoId;
        this.gestorSub = gestorSub;
    }
}
