-- Política de SLA (RF-07). Alterar a política cria uma versão nova; a anterior nunca muda (RN-04), para que os chamados
-- abertos sob ela mantenham os prazos combinados (RN-05).
CREATE TABLE politica_sla (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    nome       varchar(100) NOT NULL,
    CONSTRAINT politica_sla_nome_uk UNIQUE (nome)
);

-- Prazos em minutos de janela (RN-29); a conversão para "dias úteis" é da interface. Registro imutável: sem updated_at.
CREATE TABLE politica_sla_versao (
    id                uuid PRIMARY KEY,
    politica_id       uuid NOT NULL REFERENCES politica_sla (id),
    numero            int NOT NULL,
    resposta_alta     int NOT NULL,
    campo_alta        int NOT NULL,
    resolucao_alta    int NOT NULL,
    resposta_normal   int NOT NULL,
    campo_normal      int NOT NULL,
    resolucao_normal  int NOT NULL,
    -- Usada enquanto o gestor não define a prioridade do chamado (RN-16).
    prioridade_padrao varchar(10) NOT NULL CHECK (prioridade_padrao IN ('ALTA', 'NORMAL')),
    criada_em         timestamptz NOT NULL,
    -- sub do gestor; nulo na política padrão, criada pela migração.
    criada_por        uuid,
    CONSTRAINT politica_sla_versao_numero_uk UNIQUE (politica_id, numero),
    CONSTRAINT politica_sla_versao_positivos_ck CHECK (resposta_alta > 0 AND resposta_normal > 0),
    -- RN-30: resposta < campo < resolução em cada prioridade. A aplicação valida antes (422); isto é a última barreira.
    CONSTRAINT politica_sla_versao_ordem_ck CHECK (
        resposta_alta < campo_alta AND campo_alta < resolucao_alta
        AND resposta_normal < campo_normal AND campo_normal < resolucao_normal)
);

-- RN-04: a versão nunca é alterada nem removida, também por SQL direto.
CREATE FUNCTION politica_sla_versao_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Versão de política de SLA não pode ser alterada nem removida (RN-04).';
END;
$$;

CREATE TRIGGER politica_sla_versao_imutavel_linha
    BEFORE UPDATE OR DELETE ON politica_sla_versao
    FOR EACH ROW EXECUTE FUNCTION politica_sla_versao_imutavel();

CREATE TRIGGER politica_sla_versao_imutavel_truncate
    BEFORE TRUNCATE ON politica_sla_versao
    FOR EACH STATEMENT EXECUTE FUNCTION politica_sla_versao_imutavel();

-- Política padrão do sistema (seção 5.4): alta 120/540/1080, normal 540/1620/2700, prioridade padrão normal.
INSERT INTO politica_sla (id, created_at, updated_at, nome)
VALUES ('0199a000-0000-7000-8000-00000000c001', now(), now(), 'Padrão');

INSERT INTO politica_sla_versao (id, politica_id, numero, resposta_alta, campo_alta, resolucao_alta,
                                 resposta_normal, campo_normal, resolucao_normal, prioridade_padrao, criada_em, criada_por)
VALUES ('0199a000-0000-7000-8000-00000000c002', '0199a000-0000-7000-8000-00000000c001', 1, 120, 540, 1080,
        540, 1620, 2700, 'NORMAL', now(), NULL);
