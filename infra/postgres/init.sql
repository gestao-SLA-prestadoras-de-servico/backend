-- Executado só na primeira subida (volume vazio). Usuários e senhas vêm de .envs/.
-- Cada banco pertence ao seu usuário e não aceita conexão de mais ninguém (RN-58, RN-80).
\getenv app_user DATABASE_USER
\getenv app_password DATABASE_PASSWORD
\getenv notificacoes_user NOTIFICACOES_DB_USER
\getenv notificacoes_password NOTIFICACOES_DB_PASSWORD
\getenv keycloak_user KEYCLOAK_DB_USER
\getenv keycloak_password KEYCLOAK_DB_PASSWORD

CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
CREATE DATABASE app_principal OWNER :"app_user";
REVOKE ALL ON DATABASE app_principal FROM PUBLIC;

CREATE ROLE :"notificacoes_user" LOGIN PASSWORD :'notificacoes_password';
CREATE DATABASE notificacoes OWNER :"notificacoes_user";
REVOKE ALL ON DATABASE notificacoes FROM PUBLIC;

CREATE ROLE :"keycloak_user" LOGIN PASSWORD :'keycloak_password';
CREATE DATABASE keycloak OWNER :"keycloak_user";
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
