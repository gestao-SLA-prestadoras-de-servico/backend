package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.gestaosla.backend.shared.api.Papel;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Item da linha do tempo do chamado. Imutável (trigger no banco), sem {@code EntidadeBase}: como a auditoria, não tem
 * updated_at; a data é {@code ocorrido_em}, do Clock.
 */
@Entity
@Immutable
@Table(name = "chamado_historico")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class EventoHistorico {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID chamadoId;

    @Enumerated(EnumType.STRING)
    private TipoEventoHistorico evento;

    private UUID atorSub;

    @Enumerated(EnumType.STRING)
    private Papel atorPapel;

    private Instant ocorridoEm;

    private boolean visivelCliente;

    private boolean visivelTecnico;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> dados;

    EventoHistorico(UUID chamadoId, TipoEventoHistorico evento, UUID atorSub, Papel atorPapel, Instant ocorridoEm,
            Map<String, Object> dados) {
        this.chamadoId = chamadoId;
        this.evento = evento;
        this.atorSub = atorSub;
        this.atorPapel = atorPapel;
        this.ocorridoEm = ocorridoEm;
        this.visivelCliente = evento.visivelCliente();
        this.visivelTecnico = evento.visivelTecnico();
        this.dados = dados;
    }
}
