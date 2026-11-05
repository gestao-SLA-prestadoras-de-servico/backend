package com.gestaosla.backend.auditoria.api;

/**
 * Operações que geram registro na trilha de auditoria (RN-121). Gravado como texto: renomear um valor exige migração
 * dos registros existentes, que são imutáveis, então valor publicado não muda de nome.
 */
public enum OperacaoAuditada {

    // RN-121
    MUDANCA_ESTADO,
    ALTERACAO_PRIORIDADE,
    ATRIBUICAO,
    REATRIBUICAO,
    TRANSFERENCIA_POR_INATIVACAO,   // chamados do técnico inativado passados ao substituto
    REABERTURA,                     // RN-123
    ALTERACAO_POLITICA,
    ENVIO_EVIDENCIA,
    REPROCESSAMENTO,
    CANCELAMENTO,
    VALIDACAO_AUTOMATICA,
    EXPURGO_EVIDENCIA,              // RN-105
    ALTERACAO_PREFERENCIA_NOTIFICACAO,
    CADASTRO_USUARIO,
    INATIVACAO_USUARIO,

    // Fora da lista da RN-121, auditadas por decisão da [auditoria - 01] (RN-111 já cita a auditoria da abertura)
    ABERTURA_CHAMADO,
    PRIMEIRO_POSICIONAMENTO
}
