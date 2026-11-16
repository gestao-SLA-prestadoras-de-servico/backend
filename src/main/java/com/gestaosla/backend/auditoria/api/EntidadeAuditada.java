package com.gestaosla.backend.auditoria.api;

/** Tipo da entidade sobre a qual a operação auditada aconteceu (RN-122). Módulo novo acrescenta o seu valor. */
public enum EntidadeAuditada {
    CHAMADO,
    CONTRATO,
    USUARIO,
    POLITICA_SLA,
    EVIDENCIA,
    PREFERENCIA_NOTIFICACAO
}
