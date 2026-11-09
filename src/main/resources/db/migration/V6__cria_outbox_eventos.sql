-- Tabela de saída (outbox, seção 3.6; RF-49, RN-113): o evento é gravado na mesma transação da operação de negócio
-- e publicado depois no RabbitMQ. Nome no plural por ser o da especificação (exceção à convenção do singular).
CREATE TABLE outbox_eventos (
    id           uuid PRIMARY KEY,
    -- Vai na mensagem; os consumidores descartam repetições por ele (RN-55). Uma linha por canal, cada uma com o seu.
    evento_id    uuid NOT NULL UNIQUE,
    tipo         varchar(50) NOT NULL,
    exchange     varchar(100) NOT NULL,
    routing_key  varchar(100) NOT NULL,
    payload      jsonb NOT NULL,
    criado_em    timestamptz NOT NULL,
    publicado_em timestamptz,
    tentativas   int NOT NULL DEFAULT 0,
    ultimo_erro  text
);

-- O publicador lê só os pendentes, em ordem de criação (RN-114).
CREATE INDEX outbox_eventos_pendentes_idx ON outbox_eventos (criado_em) WHERE publicado_em IS NULL;
