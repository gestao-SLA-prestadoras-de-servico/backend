package com.gestaosla.backend.auditoria.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
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
 * Linha da trilha de auditoria. Exceção à EntidadeBase (docs/convencoes-banco.md): nunca é alterada, então não tem
 * updated_at; a data é {@code ocorrido_em}, do Clock, base dos 5 anos de retenção (RN-106).
 */
@Entity
@Table(name = "auditoria")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RegistroAuditoria {

    enum TipoAtor { USUARIO, SISTEMA }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private Instant ocorridoEm;

    @Enumerated(EnumType.STRING)
    private TipoAtor atorTipo;

    private UUID atorSub;

    @Enumerated(EnumType.STRING)
    private Papel perfil;

    @Enumerated(EnumType.STRING)
    private OperacaoAuditada operacao;

    @Enumerated(EnumType.STRING)
    private EntidadeAuditada entidadeTipo;

    private UUID entidadeId;

    private UUID chamadoId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> dados;

    RegistroAuditoria(Instant ocorridoEm, TipoAtor atorTipo, UUID atorSub, Papel perfil, OperacaoAuditada operacao,
            EntidadeAuditada entidadeTipo, UUID entidadeId, UUID chamadoId, Map<String, Object> dados) {
        this.ocorridoEm = ocorridoEm;
        this.atorTipo = atorTipo;
        this.atorSub = atorSub;
        this.perfil = perfil;
        this.operacao = operacao;
        this.entidadeTipo = entidadeTipo;
        this.entidadeId = entidadeId;
        this.chamadoId = chamadoId;
        this.dados = dados;
    }
}
