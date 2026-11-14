-- Atendimento: cada passagem do técnico pelo chamado (RN-70). Toda evidência pertence a um atendimento (RN-71). A
-- reabertura cria um novo (RN-95); a reatribuição e o cancelamento interrompem o atual, sem apagar nada (RN-21).
CREATE TABLE atendimento (
    id                uuid PRIMARY KEY,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    chamado_id        uuid NOT NULL REFERENCES chamado (id),
    tecnico_sub       uuid NOT NULL,
    estado            varchar(20) NOT NULL CHECK (estado IN ('PENDENTE', 'EM_ANDAMENTO', 'CONCLUIDO', 'INTERROMPIDO')),
    -- Início do atendimento presencial (RF-22), do Clock.
    iniciado_em       timestamptz,
    -- Conclusão ou interrupção, do Clock.
    encerrado_em      timestamptz,
    descricao_tecnica varchar(4000),
    version           bigint NOT NULL
);

CREATE INDEX atendimento_chamado_id_idx ON atendimento (chamado_id);

-- No máximo um atendimento corrente (PENDENTE ou EM_ANDAMENTO) por chamado.
CREATE UNIQUE INDEX atendimento_corrente_uk ON atendimento (chamado_id) WHERE estado IN ('PENDENTE', 'EM_ANDAMENTO');
