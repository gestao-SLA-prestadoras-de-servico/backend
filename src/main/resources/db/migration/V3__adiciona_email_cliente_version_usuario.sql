-- Campos do cadastro de usuário ([autenticação - 04]): e-mail, empresa do cliente e bloqueio otimista (RN-117).

-- E-mail único sem diferenciar maiúsculas (o Keycloak também o guarda em minúsculas).
-- O gestor inicial recebe o e-mail do usuário "gestor" de infra/keycloak/realm-flowsla.json.
ALTER TABLE usuario ADD COLUMN email varchar(254);
UPDATE usuario SET email = 'gestor@flowsla.local' WHERE sub = '0199a000-0000-7000-8000-000000000001';
ALTER TABLE usuario ALTER COLUMN email SET NOT NULL;
CREATE UNIQUE INDEX usuario_email_uk ON usuario (lower(email));

-- Empresa cliente do usuário de perfil CLIENTE; nula para técnico e gestor.
-- A FK entra na [cadastro - 01], junto com a tabela da empresa.
ALTER TABLE usuario ADD COLUMN cliente_id uuid;

ALTER TABLE usuario ADD COLUMN version bigint NOT NULL DEFAULT 0;
