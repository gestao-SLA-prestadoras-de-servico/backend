-- Trilha de auditoria (RF-41, RN-120 a RN-124). Imutável durante os 5 anos de retenção (RN-106): a regra vale também
-- para SQL direto, não só para o código. Sem updated_at: o registro nunca é alterado (exceção à EntidadeBase).
CREATE TABLE auditoria (
    id            uuid PRIMARY KEY,
    ocorrido_em   timestamptz NOT NULL,
    ator_tipo     varchar(10) NOT NULL CHECK (ator_tipo IN ('USUARIO', 'SISTEMA')),
    ator_sub      uuid,
    perfil        varchar(20),
    operacao      varchar(50) NOT NULL,
    entidade_tipo varchar(30) NOT NULL,
    entidade_id   uuid NOT NULL,
    chamado_id    uuid,
    dados         jsonb NOT NULL DEFAULT '{}',
    -- Usuário tem sub e perfil; o sistema, nenhum dos dois.
    CONSTRAINT auditoria_ator_ck CHECK (
        (ator_tipo = 'USUARIO' AND ator_sub IS NOT NULL AND perfil IS NOT NULL)
        OR (ator_tipo = 'SISTEMA' AND ator_sub IS NULL AND perfil IS NULL))
);

-- Filtros da consulta da trilha (RF-40): período, usuário, entidade e chamado.
CREATE INDEX auditoria_ocorrido_em_idx ON auditoria (ocorrido_em);
CREATE INDEX auditoria_ator_sub_idx ON auditoria (ator_sub);
CREATE INDEX auditoria_entidade_idx ON auditoria (entidade_tipo, entidade_id);
CREATE INDEX auditoria_chamado_id_idx ON auditoria (chamado_id);

-- RN-124: nenhum UPDATE; DELETE só de registro com mais de 5 anos (RN-107, feito pela Rotina de Retenção).
CREATE FUNCTION auditoria_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Registro de auditoria não pode ser alterado (RN-124).';
    END IF;
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'A trilha de auditoria não pode ser esvaziada (RN-124).';
    END IF;
    IF OLD.ocorrido_em > now() - interval '5 years' THEN
        RAISE EXCEPTION 'Registro de auditoria % ainda está no período de retenção de 5 anos (RN-124).', OLD.id;
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER auditoria_imutavel_linha
    BEFORE UPDATE OR DELETE ON auditoria
    FOR EACH ROW EXECUTE FUNCTION auditoria_imutavel();

-- TRUNCATE não dispara trigger de linha.
CREATE TRIGGER auditoria_imutavel_truncate
    BEFORE TRUNCATE ON auditoria
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria_imutavel();

-- O usuário da aplicação (dono da tabela, porque o Flyway roda com ele) fica sem UPDATE e TRUNCATE.
-- Limite: como dono, ele ainda poderia desfazer isto e desligar os triggers com um comando deliberado.
REVOKE UPDATE, TRUNCATE ON auditoria FROM CURRENT_USER;
