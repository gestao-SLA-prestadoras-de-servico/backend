-- Apuração de SLA congelada no encerramento do chamado (RF-36, RN-68): uma linha por relógio, que nunca muda. É a
-- fotografia que o relatório gerencial usa. Chamado cancelado não tem apuração (RN-19).
-- Sem FK em chamado_id: a tabela chamado ainda não existe. A FK entra na migração que criar o chamado.
CREATE TABLE apuracao_sla (
    id                       uuid PRIMARY KEY,
    chamado_id               uuid NOT NULL,
    tipo                     varchar(10) NOT NULL CHECK (tipo IN ('RESPOSTA', 'CAMPO', 'RESOLUCAO')),
    -- Copiados do chamado no encerramento, para o relatório não depender do estado atual do chamado.
    cliente_id               uuid NOT NULL REFERENCES cliente (id),
    prioridade               varchar(10) NOT NULL CHECK (prioridade IN ('ALTA', 'NORMAL')),
    -- Técnico responsável no encerramento: é quem leva o crédito no relatório por técnico.
    tecnico_sub              uuid,
    -- Do relógio do último ciclo.
    prazo_minutos            int NOT NULL CHECK (prazo_minutos > 0),
    vence_em                 timestamptz NOT NULL,
    cumprido_em              timestamptz,
    violado_em               timestamptz,
    -- VIOLADO se o relógio foi violado em qualquer ciclo (RN-69).
    resultado                varchar(10) NOT NULL CHECK (resultado IN ('CUMPRIDO', 'VIOLADO')),
    violou_em_ciclo_anterior boolean NOT NULL,
    congelada_em             timestamptz NOT NULL,
    CONSTRAINT apuracao_sla_relogio_uk UNIQUE (chamado_id, tipo),
    CONSTRAINT apuracao_sla_resultado_ck CHECK (
        resultado = 'VIOLADO' OR (violado_em IS NULL AND NOT violou_em_ciclo_anterior))
);

CREATE INDEX apuracao_sla_cliente_id_idx ON apuracao_sla (cliente_id);
CREATE INDEX apuracao_sla_tecnico_sub_idx ON apuracao_sla (tecnico_sub);

-- RN-68: a apuração gravada nunca é alterada nem removida, também por SQL direto.
CREATE FUNCTION apuracao_sla_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Apuração de SLA congelada não pode ser alterada nem removida (RN-68).';
END;
$$;

CREATE TRIGGER apuracao_sla_imutavel_linha
    BEFORE UPDATE OR DELETE ON apuracao_sla
    FOR EACH ROW EXECUTE FUNCTION apuracao_sla_imutavel();

CREATE TRIGGER apuracao_sla_imutavel_truncate
    BEFORE TRUNCATE ON apuracao_sla
    FOR EACH STATEMENT EXECUTE FUNCTION apuracao_sla_imutavel();
