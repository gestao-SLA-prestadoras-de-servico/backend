# [autenticação - 03] Criar o cliente da Admin API do Keycloak

> Requisitos: RN-127 (conta criada pelo app-principal via Admin API), RN-131 (papéis só alterados pelo app-principal), RN-137 (timeout e retry, conta de serviço própria) · Depende de: [autenticação - 01]

## Contexto

O app-principal cria, desabilita e reabilita contas no Keycloak quando o gestor cadastra ou inativa usuários. Todas essas chamadas precisam ficar em um único adaptador, com timeout e retry, porque são chamadas externas síncronas. A criação de conta não pode ser repetida às cegas: um retry após timeout poderia criar a mesma conta duas vezes.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| O adaptador cria usuário, atribui papel, dispara o e-mail de definição de senha, desabilita, reabilita e remove contas | Interface `identidade.api.KeycloakAdminClient`, implementada só por `identidade.internal.KeycloakAdminAdapter` (Admin REST API do Keycloak). |
| Keycloak lento além do timeout configurado gera erro tratado, sem travar a requisição | `connect-timeout` e `read-timeout` no `RestClient` (`KeycloakAdminConfig`). Estourou → `IdpIndisponivelException` → 503 `SERVICO_INDISPONIVEL`. Cada chamada HTTP é limitada pelo timeout; o número de chamadas é limitado: operação idempotente, até 3 tentativas com esperas de 200 e 400 ms (`atribuirPapel` faz 2 chamadas por tentativa); criação, até 5 chamadas à Admin API (POST, busca, POST, busca e o PUT que atualiza uma conta reaproveitada). O pedido do token da conta de serviço, quando o cache está vazio, soma uma chamada. |
| Retry só acontece em operações idempotentes | `@Retry(name = "keycloakAdmin")` do Resilience4j só em `atribuirPapel`, `desabilitar`, `reabilitar`, `remover` e `buscarPorUsername`. `enviarDefinicaoSenha` e `criarUsuario` não têm `@Retry`. O retry só repete `IdpIndisponivelException` (rede, timeout, 5xx). |
| Após timeout na criação, a conta nunca fica duplicada | `criarUsuario` não repete às cegas: depois de uma falha, busca pelo username. Se a conta foi gravada, devolve o `sub`; se não, faz **uma** nova tentativa. Um 409 é conferido pelo username: pode ser a conta da primeira tentativa, ou sobra de um cadastro anterior que não terminou. Se a conferência falhar, sai 503 sem novo POST. |
| Usuário já existente retorna 409; Keycloak indisponível retorna 503 | `UsuarioJaExisteException` (`ConflitoException`, 409 `USUARIO_JA_EXISTE`) quando o username é de um usuário com registro local na tabela `usuario`, ou o e-mail é de outra conta. Conta com o mesmo username **sem** registro local não é "usuário já existente": é reaproveitada (ver Decisões); rede/timeout/5xx → `IdpIndisponivelException` (`ServicoIndisponivelException`, 503). Os dois passam pelo `GlobalExceptionHandler`. |
| Credenciais da service account vêm de variáveis de ambiente | `KEYCLOAK_ADMIN_CLIENT_SECRET` e `KEYCLOAK_ADMIN_SERVER_URL`. Realm e client id são fixos (`flowsla` / `app-principal-admin`, os mesmos do `realm-flowsla.json`). |
| Testes de integração com Keycloak real (Testcontainers) criados e passando | `KeycloakAdminClientIntegrationTest`: Keycloak 26.4 com o `realm-flowsla.json` + Mailpit. |

## Passos técnicos realizados

- [x] **Interface `KeycloakAdminClient` no módulo identidade**: em `identidade.api`, com `NovoUsuarioKeycloak` e `UsuarioKeycloak`.
- [x] **Token da service account (client credentials) com cache**: pedido a `/realms/flowsla/protocol/openid-connect/token`, guardado até pouco antes de expirar (hora do `Clock` injetado, RN-62). Se o Keycloak recusar o token (401), ele é renovado uma vez.
- [x] **`criarUsuario`, `atribuirPapel`, `enviarDefinicaoSenha`, `desabilitar`, `reabilitar`, `remover`, `buscarPorUsername`**: `desabilitar`/`reabilitar` usam `PUT` parcial (`{"enabled": ...}`), que mantém os outros dados da conta; `remover` trata 404 como já removida (compensação, RN-136).
- [x] **Timeouts de conexão e leitura por propriedade**: `app.keycloak.admin.connect-timeout=2s` e `read-timeout=5s`.
- [x] **Retry com backoff (Resilience4j)**: `resilience4j-spring-boot4` 2.4.0 + `spring-boot-starter-aspectj`. Configuração em `resilience4j.retry.instances.keycloakAdmin.*`: 3 tentativas (contando a primeira), espera de 200 ms com backoff exponencial ×2, e só `IdpIndisponivelException` é repetida.
- [x] **Na criação, buscar por username antes de tentar de novo**
  A mesma conferência decide entre devolver o `sub`, reaproveitar uma conta órfã ou lançar 409.
- [x] **Erros do Keycloak → exceções do domínio**: rede/timeout/5xx → `IdpIndisponivelException` (503); 409 → `UsuarioJaExisteException`; 404 → `RecursoNaoEncontradoException`; outro 4xx (segredo errado, permissão faltando na conta de serviço) → `IllegalStateException` (500 `ERRO_INTERNO`, sem retry, causa no log).
- [x] **Preparação para a [autenticação - 04]: migração `V3__adiciona_email_cliente_version_usuario.sql`** (fora do checklist desta task, pedida para deixar a tabela pronta)
  Na tabela `usuario`: `email varchar(254) NOT NULL` com índice único em `lower(email)` (`usuario_email_uk`); `cliente_id uuid` nulo, sem FK por enquanto; `version bigint NOT NULL` (RN-117). O gestor inicial recebe `gestor@flowsla.local`. A entidade `Usuario` mapeia `email`, `clienteId` e `@Version`.
- [x] **Testes com Testcontainers (Keycloak) e com latência simulada**: `KeycloakAdminClientIntegrationTest` (Keycloak real) e `KeycloakAdminAdapterTest` (unitário, Keycloak simulado com `HttpServer` do JDK e o retry real via proxy).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Biblioteca de retry | Resilience4j (`resilience4j-spring-boot4`), por anotação e propriedades. **Substitui a escolha da plataforma-01** (`@Retryable` do Spring Framework 7) | O motivo técnico da plataforma-01 era só existir o starter do Boot 3. O `resilience4j-spring-boot4` 2.4.0 saiu em março de 2026. É o padrão de mercado, é a mesma biblioteca que o ms-notificacoes usará no circuit breaker do SMTP (RN-47, RN-49, RN-50) e já publica as métricas no Prometheus. Fica fora do BOM do Boot: versão em `resilience4j.version` no `pom.xml`. |
| HTTP client | `RestClient` do `spring-boot-starter-restclient` (builder do Boot) | As chamadas ao Keycloak entram em `http.client.requests` no Prometheus. |
| Criação de conta | Sem `@Retry`; lógica própria (conferir e no máximo uma nova tentativa) | Um retry genérico repetiria o POST às cegas. Conferir pelo username é o que garante não duplicar, e limitar a uma nova tentativa limita o tempo total. |
| Conferência que falha após timeout | 503, sem novo POST | Sem conseguir consultar, não se sabe se a conta existe; tentar de novo poderia gerar um 409 falso para a própria conta. |
| 4xx que não é 409/404 | 500 `ERRO_INTERNO`, sem retry | É erro de configuração do nosso lado (segredo, permissão), não indisponibilidade. Tratar como 503 esconderia o problema. |
| Onde tratar 409/503 | `ConflitoException(CodigoErro, ...)` e `ServicoIndisponivelException` em `shared.api`, no `GlobalExceptionHandler`; o identidade só estende | Um único handler para toda a API, como na plataforma-05. O shared não pode depender do identidade. |
| `case 409` genérico | Removido do `GlobalExceptionHandler` | Fazia qualquer 409 do Spring MVC sair como `CONFLITO_VERSAO` (bloqueio otimista), que é outra situação. |
| Mensagem do 503 | Fixa; causa só no log | Não expor endereço nem detalhe do Keycloak ao cliente. |
| Conta órfã (mesmo username, sem registro local) | Reaproveitada no próprio `criarUsuario`: recebe o e-mail, nome e sobrenome novos, é habilitada, e o `sub` dela é devolvido | Sobra quando a conferência após timeout falha ou quando a compensação da [autenticação - 04] não consegue remover a conta. Sem isso, o mesmo username receberia 409 para sempre. Fica no adaptador para quem cadastra não precisar saber de órfãs; o adaptador consulta o `UsuarioRepository`, do mesmo módulo. |
| E-mail na tabela `usuario` | `NOT NULL`, único em `lower(email)` | Ana@x e ana@x são o mesmo e-mail, como no Keycloak. O banco barra a duplicidade mesmo se o Keycloak deixar passar. |
| `cliente_id` sem FK | A FK entra na [cadastro - 01], numa migração própria | A tabela da empresa ainda não existe. Nulo para técnico e gestor. |
| 409 por e-mail com username diferente | Continua 409 | A busca é por username. E-mail igual com outro username pode ser outra pessoa, e reaproveitar trocaria o username da conta de alguém. |

## Atenção ao atualizar um ambiente existente

- Acrescente `KEYCLOAK_ADMIN_SERVER_URL` ao `.envs/.env.<ambiente>` (ver `.example`). Sem ela o backend não sobe.
- `KEYCLOAK_ADMIN_CLIENT_SECRET` precisa ser igual ao segredo importado no realm (é a mesma variável, lida pelo Keycloak e pelo backend).
- Dependências novas no `pom.xml`: **reinicie o container do backend** (ou `make build-dev`). Com o dev já rodando, o devtools reinicia a aplicação sozinho, mas com o classpath antigo, e ela não sobe: falta o bean `RestClient.Builder`.
- A `V3` roda sozinha na próxima subida do backend. Ela só preenche o e-mail do gestor inicial; se o banco tiver outra linha em `usuario` (inserida à mão), o `SET NOT NULL` falha. Nesse caso, preencha o `email` dessa linha ou rode `make run-dev-down`.
- Para a [autenticação - 04]: o registro local tem de ser gravado com o `sub` devolvido pelo `criarUsuario`. É a existência dele na tabela `usuario` que separa um usuário cadastrado de uma conta órfã.

## Como verificar

Ver `docs/testes/autenticacao-03-adaptador-keycloak-admin.md`.
