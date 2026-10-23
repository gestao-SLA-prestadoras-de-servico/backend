package com.gestaosla.backend.shared.api;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Base de toda entidade: id UUID v7 e created_at/updated_at em timestamptz.
 * Entidades com concorrência declaram o próprio {@code @Version}. Ver docs/convencoes-banco.md.
 */
@MappedSuperclass
@Getter
public abstract class EntidadeBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
