# Testes — [autenticação - 02] Validar token JWT e restringir endpoints por papel

> Executados em 27/10/2026, na branch `feat/autenticação` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers (`pgvector/pgvector:pg17`). Verificação manual no ambiente dev (`make ci-run-dev`) com o Keycloak real.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | `GlobalExceptionHandlerTest` 10, `ModularidadeTest` 2, `RelogioArquiteturaTest` 1, `BackendApplicationTests` 6, `UsuarioMeTest` 1, `AutorizacaoTest` 12; 0 falhas | ✅ |
| 2 | Sem token | `shouldReturn401WithoutToken` | 401 Problem Details `NAO_AUTENTICADO` | passou | ✅ |
| 3 | Outro issuer | `shouldReturn401ForTokenFromAnotherIssuer` (token válido, só o `iss` diferente) | 401 Problem Details | passou | ✅ |
| 4 | `aud` errado | `shouldReturn401ForTokenWithWrongAudience` (`aud` sem `app-principal`) | 401 Problem Details | passou | ✅ |
| 5 | Assinado com outra chave | `shouldReturn401ForTokenSignedWithAnotherKey` | 401 Problem Details | passou | ✅ |
| 6 | Papel errado, três perfis | `shouldAllowOnlyTheTokenRole` (parametrizado CLIENTE/TECNICO/GESTOR) | 200 no próprio endpoint; 403 `ACESSO_NEGADO` nos outros dois | passou nos três | ✅ |
| 7 | Inativo com token válido | `shouldReturn401ForInactiveUserWithValidToken` | 401 | passou | ✅ |
| 8 | `sub` sem registro local | `shouldReturn401ForSubWithoutLocalRecord` | 401 | passou | ✅ |
| 9 | Perfil local não é usado | `shouldAuthorizeByTokenRoleNotLocalProfile` (local `CLIENTE`, token `GESTOR`) | 200 em gestor; 403 em cliente | passou | ✅ |
| 10 | Papéis padrão do Keycloak ignorados | `shouldIgnoreKeycloakDefaultRoles` (só `offline_access`, `default-roles-flowsla`) | 403 em gestor | passou | ✅ |
| 11 | CORS | `shouldAllowCorsOnlyFromFrontendOrigin` | preflight do frontend 200 + `Access-Control-Allow-Origin`; outra origem 403 sem o header | passou (após correção, ver obs. 1) | ✅ |
| 12 | Dev: token real do gestor | Script PKCE da autenticação-01 contra `localhost:8081` → `GET localhost:8080/api/usuarios/me` | 200 | 200 com o gestor | ✅ |
| 13 | Dev: token adulterado | Mesmo token com os últimos 4 caracteres da assinatura trocados | 401 `application/problem+json` | 401 `application/problem+json`, `NAO_AUTENTICADO` | ✅ |
| 14 | Dev: inativo com token válido | `UPDATE usuario SET ativo=false` no gestor; mesma requisição; depois `ativo=true` | 401; volta a 200 | 401 `NAO_AUTENTICADO`; 200 após reativar | ✅ |
| 15 | Dev: CORS | `curl -X OPTIONS` com `Origin: http://localhost:5173` e com `Origin: http://malicioso.example` | 200 + header; 403 | `200` com `Access-Control-Allow-Origin: http://localhost:5173`; `403` | ✅ |

## Observações

1. **Achado:** na primeira execução o preflight não trazia `Access-Control-Allow-Origin`. O `.cors()` do Spring Security procura o bean pelo **nome** `corsConfigurationSource`, e o método se chamava `cors`. Renomeado.
2. **Achado:** a primeira ideia para os testes (`public-key-location` com um par PEM) não serve. A condição do Boot só usa a chave pública se `issuer-uri` **não** estiver definido, então o `iss` deixaria de ser conferido. Por isso o `TokenTeste` serve um JWKS por HTTP e os testes usam o mesmo caminho da produção.
3. **Não verificado:** token expirado. É o `JwtTimestampValidator` padrão do Spring, sempre ativo junto do validador de issuer. Não foi escrito teste próprio.
4. **Não verificado:** o 403 que vem de **regra de URL** (filtro de autorização). Hoje toda restrição de papel é por `@PreAuthorize`, que lança `AccessDeniedException` dentro do MVC. O handler do filtro está configurado, mas nenhuma regra de URL por papel o exercita.
