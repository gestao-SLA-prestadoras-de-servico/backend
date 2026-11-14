-- Relógios de SLA do chamado (RF-30, RN-23 a RN-28): três por ciclo, cada um com prazo próprio. Os prazos são
-- calculados e gravados na abertura, não a cada consulta (RN-64). Um ciclo novo nasce na reabertura (RN-96); o anterior
-- fica inativo, como histórico.
-- Sem FK em chamado_id: a tabela chamado ainda não existe. A FK entra na migração que criar o chamado.
CREATE TABLE relogio_sla (
    id            uuid PRIMARY KEY,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    chamado_id    uuid NOT NULL,
    tipo          varchar(10) NOT NULL CHECK (tipo IN ('RESPOSTA', 'CAMPO', 'RESOLUCAO')),
    ciclo         int NOT NULL CHECK (ciclo >= 1),
    ativo         boolean NOT NULL,
    -- Instante a partir do qual o relógio conta: abertura, ou reabertura nos ciclos seguintes. Compõe o eventoId da
    -- violação (RN-54).
    marco_inicial timestamptz NOT NULL,
    -- Em minutos de janela (RN-29).
    prazo_minutos int NOT NULL CHECK (prazo_minutos > 0),
    vence_em      timestamptz NOT NULL,
    cumprido_em   timestamptz,
    -- Sempre igual a vence_em: o horário real do estouro, não o da detecção (RN-67).
    violado_em    timestamptz,
    version       bigint NOT NULL,
    CONSTRAINT relogio_sla_ciclo_uk UNIQUE (chamado_id, tipo, ciclo),
    CONSTRAINT relogio_sla_violado_ck CHECK (violado_em IS NULL OR violado_em = vence_em)
);

-- No máximo um relógio ativo por tipo em cada chamado.
CREATE UNIQUE INDEX relogio_sla_ativo_uk ON relogio_sla (chamado_id, tipo) WHERE ativo;

-- Rotina de 15 minutos ([chamado - 14]): relógios correndo, em ordem de vencimento.
CREATE INDEX relogio_sla_correndo_idx ON relogio_sla (vence_em)
    WHERE ativo AND cumprido_em IS NULL AND violado_em IS NULL;
