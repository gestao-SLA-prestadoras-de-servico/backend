# Testes — [autenticação - 01] Configurar o realm do Keycloak

> Executados em 27/10/2026, na branch `feat/autenticação` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04). Ambiente limpo: stack de test num projeto compose descartável (`-p kccheck`, Postgres em tmpfs, `.env.test` copiado do `.example`), depois derrubado com `down -v`.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | `GlobalExceptionHandlerTest` 10, `ModularidadeTest` 2, `RelogioArquiteturaTest` 1, `BackendApplicationTests` 6, `UsuarioMeTest` 2; 0 falhas | ✅ |
| 2 | Seed e busca pelo `sub` | `UsuarioMeTest.shouldRecognizeInitialGestorBySub` (JWT do spring-security-test) | 200, `perfil GESTOR` | passou | ✅ |
| 3 | `sub` sem registro local | `UsuarioMeTest.shouldReturn404WhenSubHasNoLocalRecord` | 404, `RECURSO_NAO_ENCONTRADO` | passou | ✅ |
| 4 | Subida limpa com import | `docker compose -p kccheck ... test.yml up -d --wait --build` | todos saudáveis, realm importado | todos saudáveis; log `Realm 'flowsla' imported` | ✅ |
| 5 | Login do gestor com Authorization Code + PKCE | Script Python na rede `flowsla-test`: `/auth` com `code_challenge` S256, POST do formulário de login, troca do `code` com `code_verifier` | token emitido | token emitido | ✅ |
| 6 | Claims do token | Decodificação do access token | `realm_access.roles` com `GESTOR`; `aud` com `app-principal` e `ms-notificacoes`; `sub` fixo | `roles [default-roles-flowsla, offline_access, GESTOR, uma_authorization]`, `aud [ms-notificacoes, app-principal, account]`, `sub 0199a000-…-000000000001` | ✅ |
| 7 | PKCE obrigatório | `/auth` sem `code_challenge` | erro | redirect com `error=invalid_request`, "Missing parameter: code_challenge_method" | ✅ |
| 8 | App-principal reconhece o gestor (token real) | `GET backend-app:8080/api/usuarios/me` com o token do item 5 | 200 com o gestor | `200 {"sub":"0199a000-…-000000000001","nome":"Gestor Inicial","perfil":"GESTOR"}` | ✅ |
| 9 | Sem token | `GET /api/usuarios/me` | 401 | 401 | ✅ |
| 10 | Conta de serviço | `client_credentials` do `app-principal-admin` | token; `realm-management` só com o pedido | `view-realm, manage-users, view-users` (+ `query-users`, `query-groups`, compostos de `view-users`) | ✅ |
| 11 | Sem permissão além do necessário | `GET /admin/realms/flowsla/clients` com o token da conta de serviço | 403 | 403 | ✅ |
| 12 | E-mail de definição de senha no Mailpit | `PUT /admin/realms/flowsla/users/<sub>/execute-actions-email ["UPDATE_PASSWORD"]` com a conta de serviço; `GET mailpit:8025/api/v1/messages` | e-mail para o gestor | 204; Mailpit com `gestor@flowsla.local` / "Update Your Account" | ✅ |
| 13 | `iss` em dev | Recriado só o Keycloak do ambiente dev em execução (`up -d --no-deps keycloak`); `/.well-known/openid-configuration` pelo host; `certs` por `keycloak:8080` na rede `flowsla-dev` | `issuer http://localhost:8081/realms/flowsla`; certs 200 | os dois conforme o esperado; realm importado no banco existente | ✅ |
| 14 | Fluxo completo no **dev** (token real pelo navegador → backend) | `make run-dev-down && make ci-run-dev`; o mesmo script dos itens 5–12, do host, contra `localhost:8081` / `localhost:8080` / `localhost:8025` | `iss localhost:8081`; `/me` 200 com o gestor; demais itens iguais ao test | `iss http://localhost:8081/realms/flowsla`, `/me` 200 `perfil GESTOR`, sem token 401, conta de serviço 204/403, e-mail no Mailpit | ✅ |

## Observações

1. **Achado (item 14):** na primeira tentativa no dev, o backend não subiu: `Migration checksum mismatch for migration version 2`. O volume de dev guardava um `V2__tmp.sql` de 20/10 (teste temporário, fora do git) com a tabela `tmp`. Resolvido com `make run-dev-down`. Quem tiver um volume de dev com o mesmo resíduo passa pelo mesmo erro (ver `flyway_schema_history`). Também: o Keycloak marca os cookies de login como `Secure`. Navegadores os enviam em `http://localhost`, mas clientes como o `http.cookiejar` do Python não. O script de teste trata isso, e para o frontend nada muda.
2. **Não verificado:** prod. Lá o `KEYCLOAK_ISSUER_URI` é placeholder e o Keycloak não tem `KC_HOSTNAME`; ver "Atenção" na task.
3. **Não verificado:** o formato do 401 para token **inválido** (assinatura, `iss` ou `aud` errados). O esperado é que ele venha do `BearerTokenAuthenticationEntryPoint` do Spring, com `WWW-Authenticate` e sem Problem Details. Requisição **sem** token continua saindo em Problem Details (`BackendApplicationTests`). Cobrir na task de autorização.
