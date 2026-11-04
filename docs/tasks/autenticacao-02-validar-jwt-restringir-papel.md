# [autenticação - 02] Validar token JWT e restringir endpoints por papel

> Requisitos: RF-01 (autenticação por IdP externo), RF-02 (autorização por papel), RN-130 (papel sempre do token) · Depende de: [plataforma - 01], [plataforma - 05], [autenticação - 01]. A empresa do usuário cliente só fica disponível após [autenticação - 04].

## Contexto

O app-principal precisa aceitar apenas requisições com token válido do Keycloak e liberar cada endpoint conforme o papel do usuário. É a base de todo o isolamento entre cliente, técnico e gestor. A autenticação-01 deixou o JWT validado (assinatura, `iss`, `aud`), mas sem papéis como authorities, sem bloqueio de usuário inativo, sem CORS e sem testes com token real. Além disso, o 401 de token inválido saía no formato padrão do Spring, sem Problem Details.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Requisição sem token retorna 401 | `anyRequest().authenticated()` (só `/actuator/health/**` é público); o entry point repassa ao `GlobalExceptionHandler` → 401 `NAO_AUTENTICADO`. |
| Token de outro issuer ou com `aud` errado retorna 401 | Validação do Boot configurada na autenticação-01 (`issuer-uri` + `audiences=app-principal`). O 401 agora sai em Problem Details porque o entry point do resource server também passa pelo `GlobalExceptionHandler`. Testado com tokens assinados de verdade. |
| Papel errado → 403, para os três perfis | `UsuarioCorrenteConverter` transforma `realm_access.roles` em `ROLE_CLIENTE`/`ROLE_TECNICO`/`ROLE_GESTOR`; `@EnableMethodSecurity` + `@PreAuthorize("hasRole(...)")`. Teste parametrizado para os três perfis. |
| Usuário inativo localmente recebe 401 mesmo com token válido | O conversor busca o usuário pelo `sub` e lança `DisabledException` se estiver inativo (ou sem registro) → 401. |
| Nenhuma regra de autorização lê o perfil da tabela local | Authorities e `UsuarioCorrente.papel` vêm só do token; do registro local só se lê `ativo`. Teste: perfil local `CLIENTE` com token `GESTOR` acessa o endpoint de gestor e recebe 403 no de cliente. |
| CORS liberado apenas para a origem do frontend | `CorsConfigurationSource` com origem única `app.cors.origem` = `FRONTEND_URL`. Preflight de outra origem → 403. |
| Testes de integração criados e passando | `AutorizacaoTest` (12 casos) + `UsuarioMeTest`, com tokens reais (`TokenTeste`). |

## Passos técnicos realizados

- [x] **Starter de OAuth2 Resource Server**: já estava no `pom.xml` e configurado desde a autenticação-01.
- [x] **`issuer-uri` do realm e validador de `aud`**: já no `application.properties` (autenticação-01). Com `jwk-set-uri` definido, o Boot não faz discovery e aplica `JwtIssuerValidator` + validador de audience.
- [x] **Conversor de `realm_access.roles` para `ROLE_*`**
  `identidade.internal.UsuarioCorrenteConverter` (`Converter<Jwt, AbstractAuthenticationToken>`). Só reconhece os três papéis de `identidade.api.Papel`; `offline_access`, `uma_authorization` e `default-roles-flowsla` são ignorados.
- [x] **SecurityConfig com sessão stateless e `@EnableMethodSecurity`**
  É o `identidade.internal.SegurancaConfig` que já existia (mantido o nome em português, como o resto do código). Absorveu o `shared.internal.SegurancaErrosConfig`, removido.
- [x] **Componente `UsuarioCorrente` (sub, papel e, para cliente, empresa)**
  `identidade.api.UsuarioCorrente` (record) é o principal da autenticação: `@AuthenticationPrincipal UsuarioCorrente`. `empresaClienteId` fica `null` até a autenticação-04. O enum `Perfil` (internal) virou `identidade.api.Papel`, usado pelo token e pela cópia local.
- [x] **Filtro que rejeita usuário inativo**
  Feito no próprio conversor, e não num filtro à parte (ver Decisões).
- [x] **CORS por variável de ambiente**: `app.cors.origem=${FRONTEND_URL}` (a variável já existia nos `.envs`).
- [x] **401 e 403 em Problem Details**: os dois handlers (entry point e access denied) foram aplicados tanto no `exceptionHandling` quanto no `oauth2ResourceServer`, que tem os próprios.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Bloqueio do inativo | No `UsuarioCorrenteConverter`, não num filtro | O conversor roda dentro da autenticação do token: uma consulta só, e a exceção já vira 401 pelo entry point. Um filtro teria que reler o banco e ser posicionado na cadeia. |
| `sub` sem registro local | 401, igual ao inativo | Conta sem vínculo no sistema (seção 3.5): o token é válido, mas o usuário não existe para o app-principal. |
| Token sem nenhum dos três papéis | Autentica, sem authorities | Acessa só o que exige apenas autenticação (`/me`); qualquer `@PreAuthorize` de papel dá 403. |
| `SegurancaErrosConfig` | Removido; lógica em `SegurancaConfig` | Foi criado na plataforma-05 só porque não havia cadeia. Agora há, e o resource server precisa do mesmo handler. |
| Testes com token real | `TokenTeste`: JWKS servido pelo `HttpServer` do JDK + tokens assinados com Nimbus; `jwk-set-uri` entra por `DynamicPropertyRegistrar` | O decoder testado é o mesmo da produção (`jwk-set-uri` + `iss` + `aud`). O `public-key-location` do Boot não aceita `issuer-uri` junto, e o `jwt()` do spring-security-test pula o decoder e o conversor. Sem dependência nova. |
| Métodos/headers de CORS | GET/POST/PUT/PATCH/DELETE; `Authorization` e `Content-Type` | O que o frontend usa; sem credenciais de cookie (token no header). |

## Atenção ao atualizar um ambiente existente

- Nenhuma variável nova: o CORS usa o `FRONTEND_URL` da autenticação-01.
- Usuário desativado na tabela `usuario` perde o acesso na hora, mesmo com token válido.
- Quem chama a API de fora do navegador do frontend (ex.: Postman) não é afetado pelo CORS. Outra origem web recebe 403 no preflight.

## Como verificar

Ver `docs/testes/autenticacao-02-validar-jwt-restringir-papel.md`.
