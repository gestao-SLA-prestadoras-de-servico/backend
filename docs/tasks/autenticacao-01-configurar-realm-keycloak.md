# [autenticação - 01] Configurar o realm do Keycloak

> Requisitos: RF-01 (autenticação por IdP externo), RN-138 (e-mail de definição de senha pelo Keycloak) · Depende de: [plataforma - 02]

## Contexto

Toda a autenticação depende do Keycloak: ele guarda as contas, emite os tokens JWT e é a fonte oficial dos papéis `CLIENTE`, `TECNICO` e `GESTOR`. Antes desta task o Keycloak subia vazio, sem realm, e o backend não validava token (o resource server era só dependência). O realm agora fica versionado no repositório e é importado na subida, e existe um gestor inicial que o app-principal reconhece pelo `sub`. Sem ele ninguém cadastraria o primeiro usuário.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `docker compose up` em ambiente limpo sobe o Keycloak com o realm `flowsla` já importado | `infra/keycloak/` montado em `/opt/keycloak/data/import` e `--import-realm` no comando (`start` no base, `start-dev` em dev/test). O Keycloak só fica saudável depois do import, então o `--wait` e o `depends_on` do backend já cobrem o critério. |
| Papéis `CLIENTE`, `TECNICO` e `GESTOR` | `roles.realm` do `realm-flowsla.json`. |
| Token com `realm_access.roles` e `aud` do app-principal | `realm_access.roles` vem do client scope padrão `roles`, criado pelo Keycloak no import. O `aud` vem de dois `oidc-audience-mapper` no client `flowsla-frontend` (`app-principal` e `ms-notificacoes`). O backend exige `aud` = `app-principal` (`spring.security.oauth2.resourceserver.jwt.audiences`). |
| Gestor inicial faz login e o app-principal o reconhece pelo `sub` | Usuário `gestor` com `id` fixo no JSON (o `id` do Keycloak é o `sub`). A `V2__cria_usuario.sql` cria a tabela `usuario` e grava o gestor com o mesmo `sub`. `GET /api/usuarios/me` busca o usuário pelo `sub` do token. |
| E-mail de definição de senha chega ao Mailpit | `smtpServer` do realm aponta para `SMTP_HOST:SMTP_PORT` (Mailpit, `mailpit:1025`). |

## Passos técnicos realizados

- [x] **Criar o realm `flowsla` em `infra/keycloak/realm-flowsla.json`**
  Escrito à mão e mínimo, em vez de um export completo (cerca de 2000 linhas de padrões). O que não está no arquivo, o Keycloak cria com o padrão no import (client scopes, `default-roles-flowsla`, clients internos). Valores que mudam por ambiente ficam em placeholders `${VAR}`, resolvidos pelo Keycloak a partir do env do container.
- [x] **Papéis de realm `CLIENTE`, `TECNICO` e `GESTOR`**
- [x] **Client público do frontend (Authorization Code + PKCE)**
  `flowsla-frontend`: público, só `standardFlowEnabled`, `pkce.code.challenge.method=S256` (sem `code_challenge` o Keycloak recusa), redirect e web origin em `FRONTEND_URL`.
- [x] **Audience mapper para o app-principal e o ms-notificacoes**
  No próprio client do frontend, só no access token.
- [x] **Client confidencial `app-principal-admin`**
  Só service account (nenhum outro fluxo), segredo em `KEYCLOAK_ADMIN_CLIENT_SECRET`. O usuário `service-account-app-principal-admin` recebe só `manage-users`, `view-users` e `view-realm` de `realm-management`. `query-users`/`query-groups` aparecem no token porque são compostos de `view-users`.
- [x] **SMTP do realm apontando para o Mailpit**
  Remetente `no-reply@flowsla.local`.
- [x] **Usuário gestor inicial com `sub` fixo**
  `gestor` / `gestor@flowsla.local`, `id` `0199a000-0000-7000-8000-000000000001`, papel `GESTOR`, senha `KEYCLOAK_GESTOR_PASSWORD` (não temporária, para o ambiente subir pronto).
- [x] **Migração/seed do registro local do gestor**
  `V2__cria_usuario.sql`: tabela `usuario` (`sub` único, `nome`, `perfil`, `ativo`) e o insert do gestor. Entidade `identidade.internal.Usuario` + `UsuarioRepository.findBySub`.
- [x] **Import automático no `infra/docker-compose.yml`**
  Em dev também `KC_HOSTNAME=http://localhost:8081` e `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`: o token tirado pelo navegador sai com `iss` em `localhost:8081`, e o backend busca as chaves por `keycloak:8080`.
- [x] **Variáveis em `.envs/*.example` e credenciais no README**
  `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `KEYCLOAK_GESTOR_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`, `FRONTEND_URL`, `SMTP_HOST`, `SMTP_PORT`. Saíram `SPRING_SECURITY_USER_NAME`/`PASSWORD`: com JWT configurado o Boot não cria mais o usuário basic.
- [x] **Validação do JWT no backend (mínimo para o critério do `sub`)**
  `identidade.internal.SegurancaConfig`: cadeia stateless, sem CSRF, `/actuator/health/**` liberado, o resto autenticado por `oauth2ResourceServer(jwt)`. O `SegurancaErrosConfig` continua formatando os 401/403.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Formato do realm | JSON mínimo escrito à mão | Revisável em PR; o export completo é quase todo padrão do Keycloak e muda a cada versão. |
| Valores por ambiente | Placeholders `${VAR}` do import do Keycloak | Um único arquivo para dev/test/prod; segredos ficam nos `.envs`. |
| Senha do gestor | Definida no import, vinda do env | O ambiente sobe com login pronto. Os demais usuários definem a senha pelo e-mail (RN-128). |
| Validação do `iss` | `issuer-uri` + `jwk-set-uri` | Com `jwk-set-uri` o Spring não faz discovery: o `iss` (host público) só é conferido, e as chaves vêm pela rede interna. |
| Papéis do token como authorities | Fica para a task de autorização | `/me` só exige autenticação; mapear `realm_access.roles` sem regra que o use seria código morto. |
| Empresa cliente em `usuario` | Fica para o cadastro, com a tabela da empresa | Sem a tabela não há FK para criar. |

## Atenção ao atualizar um ambiente existente

- Acrescente ao `.envs/.env.<ambiente>` as variáveis novas (ver `.example`). Sem elas o compose passa valores vazios ao Keycloak (realm importado com senha, segredo e SMTP vazios) e o backend não sobe.
- O realm só é importado se ainda não existir. Num ambiente criado antes desta task, recriar só o Keycloak já importa (`docker compose ... up -d keycloak`). Depois de alterar o JSON, é preciso `make run-dev-down`.
- A `V2` roda sozinha na próxima subida do backend.
- Em prod, `KEYCLOAK_ISSUER_URI` precisa ser o endereço público real do Keycloak, e o `KC_HOSTNAME` dele precisa ser configurado. Hoje prod não publica a porta do Keycloak.
- O usuário basic de dev (`dev`/`dev123456`) deixou de existir: as rotas pedem `Authorization: Bearer <token>`.

## Como verificar

Ver `docs/testes/autenticacao-01-configurar-realm-keycloak.md`.
