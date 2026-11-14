-- Chamado (RF-12, RN-09 a RN-16). As transições de estado são garantidas pela entidade (RN-10, RN-13); aqui o banco só
-- confere os valores. Toda mutação usa bloqueio otimista (RN-117).
CREATE TABLE chamado (
    id                  uuid PRIMARY KEY,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    -- Número legível (#1042, RN-14). Pode ter buracos: rollback consome o número.
    numero              bigint NOT NULL UNIQUE DEFAULT nextval('chamado_numero_seq'),
    contrato_id         uuid NOT NULL REFERENCES contrato (id),
    cliente_id          uuid NOT NULL REFERENCES cliente (id),
    -- Versão da política vigente na abertura; vale até o fim, inclusive após reabertura (RN-05).
    politica_versao_id  uuid NOT NULL REFERENCES politica_sla_versao (id),
    -- Cópia da prioridade padrão da versão (imutável): a prioridade efetiva é a definida ou esta (RN-16).
    prioridade_padrao   varchar(10) NOT NULL CHECK (prioridade_padrao IN ('ALTA', 'NORMAL')),
    titulo              varchar(200) NOT NULL,
    descricao           varchar(4000) NOT NULL,
    local               varchar(300) NOT NULL,
    -- Sugerida pelo cliente, sem efeito nos prazos (RN-15).
    prioridade_sugerida varchar(10) CHECK (prioridade_sugerida IN ('ALTA', 'NORMAL')),
    -- Definida pelo gestor (RF-15); nula enquanto ele não define.
    prioridade_definida varchar(10) CHECK (prioridade_definida IN ('ALTA', 'NORMAL')),
    estado              varchar(20) NOT NULL CHECK (estado IN ('ABERTO', 'ATRIBUIDO', 'EM_ATENDIMENTO', 'RESOLVIDO',
                                                               'ENCERRADO', 'CANCELADO')),
    tecnico_sub         uuid,
    -- Primeiro posicionamento do gestor ao cliente (RF-16, RN-23).
    posicionamento      varchar(4000),
    posicionado_em      timestamptz,
    -- Do Clock: é o marco inicial dos relógios de SLA (o created_at vem do relógio da JVM).
    aberto_em           timestamptz NOT NULL,
    resolvido_em        timestamptz,
    -- Aviso prévio da validação automática (RN-34).
    aviso_enviado_em    timestamptz,
    -- Quando chegou a ENCERRADO ou CANCELADO: início dos 180 dias de retenção das evidências (RN-100).
    estado_final_em     timestamptz,
    motivo_cancelamento varchar(1000),
    version             bigint NOT NULL
);

CREATE INDEX chamado_cliente_id_idx ON chamado (cliente_id);
CREATE INDEX chamado_tecnico_sub_idx ON chamado (tecnico_sub);
CREATE INDEX chamado_estado_idx ON chamado (estado);

-- Linha do tempo do chamado, visível aos perfis indicados (o gestor vê tudo). Separada da auditoria, que é só do
-- gestor (RN-125). O evento é texto sem CHECK, como a operação da auditoria: cada task acrescenta os seus.
CREATE TABLE chamado_historico (
    id              uuid PRIMARY KEY,
    chamado_id      uuid NOT NULL REFERENCES chamado (id),
    evento          varchar(50) NOT NULL,
    -- Usuário tem sub e papel; o sistema (rotinas), nenhum dos dois.
    ator_sub        uuid,
    ator_papel      varchar(20),
    ocorrido_em     timestamptz NOT NULL,
    visivel_cliente boolean NOT NULL,
    visivel_tecnico boolean NOT NULL,
    dados           jsonb NOT NULL DEFAULT '{}',
    CONSTRAINT chamado_historico_ator_ck CHECK ((ator_sub IS NULL) = (ator_papel IS NULL))
);

CREATE INDEX chamado_historico_chamado_idx ON chamado_historico (chamado_id, ocorrido_em);

-- O histórico é uma linha do tempo: nunca é alterado nem removido, também por SQL direto.
CREATE FUNCTION chamado_historico_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Histórico do chamado não pode ser alterado nem removido.';
END;
$$;

CREATE TRIGGER chamado_historico_imutavel_linha
    BEFORE UPDATE OR DELETE ON chamado_historico
    FOR EACH ROW EXECUTE FUNCTION chamado_historico_imutavel();

CREATE TRIGGER chamado_historico_imutavel_truncate
    BEFORE TRUNCATE ON chamado_historico
    FOR EACH STATEMENT EXECUTE FUNCTION chamado_historico_imutavel();

-- Pendência da V10 e da V11: com a tabela chamado criada, os relógios e a apuração ganham a FK.
ALTER TABLE relogio_sla ADD CONSTRAINT relogio_sla_chamado_fk FOREIGN KEY (chamado_id) REFERENCES chamado (id);
ALTER TABLE apuracao_sla ADD CONSTRAINT apuracao_sla_chamado_fk FOREIGN KEY (chamado_id) REFERENCES chamado (id);
