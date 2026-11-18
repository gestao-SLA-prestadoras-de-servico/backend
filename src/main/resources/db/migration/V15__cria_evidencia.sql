-- Evidência fotográfica do atendimento (RF-24, RN-71, RN-82). O binário fica no object storage; aqui só a referência.
-- Toda evidência pertence a um atendimento (RN-71) e guarda o chamado, que compõe a chave do objeto e a auditoria (RN-104).
CREATE TABLE evidencia (
    id                uuid PRIMARY KEY,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    atendimento_id    uuid NOT NULL REFERENCES atendimento (id),
    chamado_id        uuid NOT NULL REFERENCES chamado (id),
    -- Nome enviado pelo cliente, só informativo: sem path e truncado. Não vai para a auditoria nem para a chave.
    nome              varchar(255),
    -- Do arquivo final, já sanitizado: um WebP enviado vira image/jpeg.
    tipo_conteudo     varchar(50) NOT NULL,
    tamanho           bigint NOT NULL,
    -- SHA-256 em hexadecimal minúsculo sobre os bytes sanitizados (RN-77).
    hash_sha256       varchar(64) NOT NULL,
    chave_original    varchar(300) NOT NULL,
    -- Preenchidas pelo processamento dos derivados.
    chave_miniatura   varchar(300),
    chave_visualizacao varchar(300),
    estado            varchar(20) NOT NULL CHECK (estado IN ('RECEBIDA', 'PROCESSANDO', 'DISPONIVEL', 'FALHA', 'EXPURGADA')),
    -- Do Clock, na recepção.
    enviada_em        timestamptz NOT NULL,
    expurgada_em      timestamptz,
    version           bigint NOT NULL
);

CREATE INDEX evidencia_atendimento_id_idx ON evidencia (atendimento_id);
CREATE INDEX evidencia_chamado_id_idx ON evidencia (chamado_id);

-- RN-77: o hash e a chave do original nunca mudam, nem por SQL direto. O resto da linha muda com o processamento.
CREATE FUNCTION evidencia_original_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.hash_sha256 IS DISTINCT FROM OLD.hash_sha256 OR NEW.chave_original IS DISTINCT FROM OLD.chave_original THEN
        RAISE EXCEPTION 'O hash e a chave do original da evidência % não podem ser alterados (RN-77).', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER evidencia_original_imutavel BEFORE UPDATE ON evidencia
    FOR EACH ROW EXECUTE FUNCTION evidencia_original_imutavel();
