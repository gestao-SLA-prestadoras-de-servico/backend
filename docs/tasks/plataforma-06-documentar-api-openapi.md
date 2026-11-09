# [plataforma - 06] Documentar a API com OpenAPI

> Requisitos: seção 3.1 (API REST documentada) · Depende de: [plataforma - 01], [plataforma - 05]

## Contexto

O enunciado exige API REST documentada, e o frontend precisa de um contrato confiável para gerar o cliente HTTP. A documentação deve permitir testar os endpoints com token real. Feita junto com a [autenticação - 04], que pede o `POST /usuarios` documentado, na branch `feat/autenticacao-04-plataforma-06`, criada a partir de `feat/cadastro`, para cobrir os endpoints de identidade e cadastro.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| O Swagger UI (perfil dev) autentica com token do Keycloak e executa chamadas | `API_DOCS_ENABLED=true` só no `.env.dev`. O **Authorize** faz Authorization Code + PKCE no Keycloak pelo client público novo `flowsla-swagger` (`springdoc.swagger-ui.oauth.*`); o token tem `aud: app-principal` e a API o aceita. |
| Todos os endpoints listam os códigos de erro possíveis | Um `GlobalOpenApiCustomizer` acrescenta 401, 403 e 500 a toda operação, e 400 a toda operação com corpo ou parâmetros. Os específicos (404, 409, ...) entram com `@ApiResponse(ref = RespostasErro.X)`. Cada resposta aponta para um componente com o schema `Problema` (Problem Details com `codigo`) e um exemplo. `OpenApiContratoTest` confere. |
| O build exporta openapi.json | `OpenApiContratoTest` (roda em `make integration-test` e na CI) gera `/v3/api-docs` e compara com o `docs/openapi.json` versionado. Se estiver diferente, reescreve o arquivo e falha. |

## Passos técnicos realizados

- [x] **Adicionar springdoc-openapi**: `springdoc-openapi-starter-webmvc-ui` 3.2.0, compilado contra o Spring Boot 4.1.2 (o projeto está no 4.1.1). Fora do BOM do Boot: versão em `springdoc.version` no `pom.xml`.
- [x] **Esquema de segurança**: OAuth2 Authorization Code com as URLs de login e token do realm (`app.openapi.keycloak-realm-url`, que no dev é o `KEYCLOAK_ISSUER_URI`), exigido em todas as operações.
- [x] **Uma tag por módulo**: `Identidade` e `Cadastro`, declaradas no `OpenApiConfig` e usadas com `@Tag` nos controllers. Módulo novo com endpoint acrescenta a dele.
- [x] **Exemplos de payload e de Problem Details**: `@Schema(example = ...)` em `DadosCliente`, `Endereco` e `EdicaoCliente`. Um exemplo de Problem Details por resposta de erro (`VALIDACAO` com `erros`, `CONFLITO_VERSAO`, `RECURSO_NAO_ENCONTRADO`, ...).
- [x] **Exportação do openapi.json no build**: por teste, sem plugin (ver Decisões).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Exportação | Teste gera e versiona `docs/openapi.json`, falhando se divergir; sem o `springdoc-openapi-maven-plugin` | O contrato fica versionado e revisado no PR, a CI garante que ele bate com o código, e o frontend usa o arquivo do repositório. O plugin exigiria subir a aplicação inteira no build (com PostgreSQL acessível) e deixaria o JSON só em `target/`. |
| JSON estável | `writer-with-order-by-keys` e `writer-with-default-pretty-printer`; servidor relativo `/` | Diff legível e sem mudanças aleatórias entre execuções. |
| Login no Swagger | Client próprio `flowsla-swagger`, PKCE S256, redirect só em `${SWAGGER_URL}/swagger-ui/oauth2-redirect.html`, com os mesmos audience mappers | O client do frontend fica intocado. O token é igual ao do frontend (`aud` `app-principal` e `ms-notificacoes`). |
| Ligar só em dev | Variável `API_DOCS_ENABLED`, não perfil Spring | O projeto configura ambiente por `.envs`; perfis Spring não são usados fora dos testes. Desligado, `/v3/api-docs` e `/swagger-ui` dão 404 (`OpenApiDesligadoTest`). |
| URLs de login no contrato | Propriedade `app.openapi.keycloak-realm-url` | O emissor dos testes (`http://localhost/realms/flowsla`) é falso; o contrato versionado mostra o endereço do dev. |
| Constantes das respostas | `shared.api.RespostasErro` | Os controllers estão em outros módulos e não podem importar o `shared.internal`, onde fica o `OpenApiConfig`. |
| Branch | `feat/autenticacao-04-plataforma-06` (pedido do usuário) | A `feat/plataforma` estava parada no `develop`, sem os endpoints a documentar; esta task só existe agora por causa da autenticação-04. |
| Ambiente de dev | `make run-dev-down` (escolha do usuário) | O realm só é importado num Keycloak vazio. |

## Atenção ao atualizar um ambiente existente

- **`SWAGGER_URL` é obrigatória em todos os ambientes**, mesmo com o Swagger desligado. Sem ela, o Keycloak **não sobe**: `Invalid client flowsla-swagger: A redirect URI is not a valid URI`. Acrescente ao `.envs/.env.<ambiente>` as duas variáveis novas (`API_DOCS_ENABLED`, `SWAGGER_URL`).
- O client novo só entra num realm importado do zero: `make run-dev-down`, ou criar o client à mão no console.
- Dependência nova no `pom.xml`: reinicie o container do backend (o devtools sozinho não pega o classpath novo).
- Mudou endpoint ou DTO? Rode `make integration-test`. Se o `OpenApiContratoTest` falhar, ele já reescreveu `docs/openapi.json`; confira o diff e commite junto.

## Como verificar

Ver `docs/testes/plataforma-06-documentar-api-openapi.md`.
