-- Contrato de um cliente (RF-06). Um cliente pode ter vários contratos ao mesmo tempo (RN-06).
-- A vigência é por data, no fuso do sistema: o contrato vale do primeiro instante do início até 23:59:59 do fim (RN-01).
CREATE TABLE contrato (
    id              uuid PRIMARY KEY,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    cliente_id      uuid NOT NULL REFERENCES cliente (id),
    codigo          varchar(50) NOT NULL,
    inicio_vigencia date NOT NULL,
    fim_vigencia    date NOT NULL,
    CONSTRAINT contrato_codigo_uk UNIQUE (codigo),
    CONSTRAINT contrato_vigencia_ck CHECK (fim_vigencia >= inicio_vigencia)
);

CREATE INDEX contrato_cliente_id_idx ON contrato (cliente_id);
