-- Empresa cliente (RF-05, RN-08). Nunca é apagada: inativar preserva contratos e chamados (RN-07).
CREATE TABLE cliente (
    id            uuid PRIMARY KEY,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    razao_social  varchar(200) NOT NULL,
    -- Destino de todos os e-mails do cliente (RN-08). Pode repetir entre clientes (ex.: empresas do mesmo grupo).
    email_contato varchar(254) NOT NULL,
    logradouro    varchar(200) NOT NULL,
    numero        varchar(20) NOT NULL,
    complemento   varchar(100),
    bairro        varchar(100) NOT NULL,
    cidade        varchar(100) NOT NULL,
    uf            varchar(2) NOT NULL CHECK (uf ~ '^[A-Z]{2}$'),
    cep           varchar(8) NOT NULL CHECK (cep ~ '^[0-9]{8}$'),
    ativo         boolean NOT NULL,
    version       bigint NOT NULL
);

-- Empresa do usuário de perfil CLIENTE (coluna criada na V3). Sem cascata: cliente não é apagado.
ALTER TABLE usuario ADD CONSTRAINT usuario_cliente_fk FOREIGN KEY (cliente_id) REFERENCES cliente (id);
