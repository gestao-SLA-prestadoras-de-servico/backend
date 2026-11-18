-- Preferência de cada gestor, chamado a chamado, de ser avisado quando o técnico concluir o atendimento (RF-56, RN-40).
-- A linha existe enquanto a preferência está ativa; desativar remove a linha. A trilha fica na auditoria (RN-121).
CREATE TABLE preferencia_notificacao (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    chamado_id uuid NOT NULL REFERENCES chamado (id),
    gestor_sub uuid NOT NULL,
    CONSTRAINT preferencia_notificacao_uk UNIQUE (chamado_id, gestor_sub)
);
