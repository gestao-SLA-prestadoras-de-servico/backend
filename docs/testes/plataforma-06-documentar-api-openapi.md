# Testes — [plataforma - 06] Documentar a API com OpenAPI

> Executados em 03/11/2026, na branch `feat/autenticacao-04-plataforma-06` (criada a partir de `feat/cadastro`; alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers. Verificação manual no ambiente dev recriado com `make run-dev-down` + `make ci-run-dev`.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 85 testes, 0 falhas (`OpenApiContratoTest` 3, `OpenApiDesligadoTest` 1 e os anteriores) | ✅ |
| 2 | Contrato versionado bate com o código | `OpenApiContratoTest.shouldMatchVersionedContract` | passa com o `docs/openapi.json` atual; falha e reescreve o arquivo se divergir | falhou na 1ª execução (arquivo inexistente) e na que mudou `Pageable`/`EdicaoCliente`, reescrevendo o arquivo; passou nas seguintes | ✅ |
| 3 | Códigos de erro em todos os endpoints | `shouldListErrorResponsesOnEveryOperation` | toda operação com 401, 403 e 500; `PUT /api/clientes/{id}` com 200, 400, 404 e 409; exemplo de Problem Details com `codigo` | passou | ✅ |
| 4 | Login PKCE e tags | `shouldAuthenticateWithKeycloakPkceAndTagByModule` | `authorizationUrl` do realm de dev; tags `Identidade` e `Cadastro` | passou | ✅ |
| 5 | Desligado fora do dev | `OpenApiDesligadoTest` (`springdoc.*.enabled=false`) | `/v3/api-docs` e `/swagger-ui/index.html` → 404 | passou | ✅ |
| 6 | Conteúdo do contrato | Leitura do `docs/openapi.json` | 5 operações; listagem com `page`, `size` e `sort` como query params; schemas `ClienteResponse`, `DadosCliente`, `EdicaoCliente`, `Endereco`, `PagedModelClienteResponse`, `Problema`, `UsuarioResponse`; exemplos nos campos | conforme o esperado | ✅ |
| 7 | Dev: Swagger UI servido | `GET /swagger-ui.html`, `/v3/api-docs/swagger-config`, `/swagger-ui/swagger-initializer.js` | redireciona para a UI; `oauth2RedirectUrl` do Swagger; `clientId: flowsla-swagger` e PKCE ligado | 302 → `/swagger-ui/index.html`; `oauth2RedirectUrl` `http://localhost:8080/swagger-ui/oauth2-redirect.html`; `clientId "flowsla-swagger"`, `usePkceWithAuthorizationCodeGrant true` | ✅ |
| 8 | Dev: login pelo client do Swagger | Script de login PKCE com `client_id=flowsla-swagger` e o mesmo redirect do Swagger UI; token decodificado | token emitido com `aud: app-principal` e papel do gestor | `azp flowsla-swagger`, `aud [app-principal, ms-notificacoes, account]`, `GESTOR` | ✅ |
| 9 | Dev: chamada com esse token | `GET /api/clientes` | 200 | 200 | ✅ |
| 10 | Dev: redirect não cadastrado | `/auth` do `flowsla-swagger` com `redirect_uri=http://evil.example/cb` | recusado | 400 | ✅ |
| 11 | Keycloak sem `SWAGGER_URL` | `quay.io/keycloak/keycloak:26.4` descartável com o realm novo, sem a variável | não sobe | `Invalid client flowsla-swagger: A redirect URI is not a valid URI` | ✅ (comportamento documentado) |

## Observações

1. **Achado:** com o client novo, o Keycloak não sobe sem `SWAGGER_URL` (item 11). O `KeycloakAdminClientIntegrationTest` quebrou por isso e passou a enviar a variável ao container. Os três `.example` já a trazem; ambientes existentes precisam acrescentá-la (ver "Atenção" na task).
2. **Achado:** o `Pageable` aparecia como um parâmetro-objeto `pagina` no contrato; com `@ParameterObject` virou `page`, `size` e `sort`. O record `Edicao` foi renomeado para `EdicaoCliente`, porque o nome do schema vai para o cliente gerado pelo frontend.
3. **Não verificado:** o clique no **Authorize** dentro de um navegador. O fluxo foi reproduzido pelo script com o mesmo client, o mesmo redirect e PKCE (item 8), e a configuração servida à UI foi conferida (item 7).
4. **Limite:** o 403 aparece também em `GET /api/usuarios/me`, que não exige papel. O customizer acrescenta os erros comuns a toda operação sem olhar o `@PreAuthorize`.
5. **Sem dado no dev:** o `run-dev-down` apagou os volumes de dev (escolha do usuário); o ambiente subiu do zero com o realm novo e as migrações V1–V5.
