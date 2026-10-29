-- Registro local do usuário (seção 3.5): a conta e a senha vivem no Keycloak; aqui ficam o sub e a cópia do perfil.
-- O vínculo com a empresa cliente entra junto com a tabela da empresa.
CREATE TABLE usuario (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    sub        uuid NOT NULL UNIQUE,
    nome       varchar(150) NOT NULL,
    perfil     varchar(20) NOT NULL,
    ativo      boolean NOT NULL
);

-- Gestor inicial: sem ele ninguém cadastra o primeiro usuário. O sub é o "id" do usuário "gestor"
-- em infra/keycloak/realm-flowsla.json; os dois mudam juntos.
INSERT INTO usuario (id, created_at, updated_at, sub, nome, perfil, ativo)
VALUES ('0199a000-0000-7000-8000-0000000000a1', now(), now(), '0199a000-0000-7000-8000-000000000001',
        'Gestor Inicial', 'GESTOR', true);
