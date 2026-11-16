# Testes — [autenticação - 03] Criar o cliente da Admin API do Keycloak

> Executados em 29/10/2026, na branch `feat/autenticação` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers: `pgvector/pgvector:pg17`, `quay.io/keycloak/keycloak:26.4` com o `infra/keycloak/realm-flowsla.json` e `axllent/mailpit:v1.27`.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | `GlobalExceptionHandlerTest` 12, `KeycloakAdminAdapterTest` 12, `ModularidadeTest` 2, `RelogioArquiteturaTest` 1, `BackendApplicationTests` 6, `AutorizacaoTest` 12, `UsuarioMeTest` 1, `KeycloakAdminClientIntegrationTest` 9, `UsuarioTabelaTest` 3; 0 falhas | ✅ |
| 2 | Criar e buscar por username (Keycloak real) | `shouldCreateUserAndFindItByUsername` | `sub` devolvido = o da busca; e-mail e `enabled` corretos | passou | ✅ |
| 3 | Conta órfã reaproveitada (real) | `shouldReuseOrphanAccountWithSameUsername`: conta criada e desabilitada, sem registro local; novo cadastro com o mesmo username e outro e-mail | mesmo `sub`; e-mail e nome novos; conta habilitada | passou | ✅ |
| 4 | Username de usuário cadastrado (real) | `shouldThrowConflictWhenUsernameBelongsToRegisteredUser`: linha na tabela `usuario` com o `sub` | `UsuarioJaExisteException` | passou | ✅ |
| 5 | E-mail de outra conta (real) | `shouldThrowConflictForEmailOfAnotherAccount` | `UsuarioJaExisteException` | passou | ✅ |
| 6 | Atribuir papel, duas vezes (real) | `shouldAssignRealmRoleIdempotently` + consulta a `/role-mappings/realm` | sem erro; `TECNICO` mapeado no Keycloak | passou | ✅ |
| 7 | E-mail de definição de senha (real) | `shouldSendPasswordEmailThroughKeycloakSmtp` + busca na API do Mailpit | 1 e-mail para o endereço da conta | passou | ✅ |
| 8 | Desabilitar (duas vezes) e reabilitar (real) | `shouldDisableAndReenableKeepingAccountData` | `enabled` false/true; nome e e-mail preservados | passou | ✅ |
| 9 | Remover, duas vezes (real) | `shouldRemoveIdempotently` | conta some; segunda remoção sem erro | passou | ✅ |
| 10 | Conta inexistente (real) | `shouldReturn404ForUnknownAccount` | `RecursoNaoEncontradoException` | passou | ✅ |
| 11 | Retry em operação idempotente | `shouldRetryIdempotentOperationWhileKeycloakIsUnavailable` (503, 503, 204) | sucesso na 3ª chamada | 3 PUTs, sucesso | ✅ |
| 12 | Desiste após o máximo | `shouldGiveUpWith503AfterMaxAttempts` | `IdpIndisponivelException` após 3 chamadas | passou | ✅ |
| 13 | E-mail sem retry | `shouldNotRetryPasswordEmail` (503) | 1 chamada só | passou | ✅ |
| 14 | 4xx de configuração | `shouldNotRetryClientErrorsAndReportThemAsInternalError` (token 401) | `IllegalStateException` (500), 1 pedido de token | passou | ✅ |
| 15 | Keycloak lento além do timeout | `shouldFailWithinTimeoutWhenKeycloakIsSlow` (resposta em 2 s, timeout 150 ms) | `IdpIndisponivelException` em menos de 1,5 s | passou | ✅ |
| 16 | Timeout na criação com a conta já gravada | `shouldReturnExistingSubWhenCreationTimesOutAfterKeycloakSavedTheAccount` | devolve o `sub` existente; 1 POST | passou | ✅ |
| 17 | 409 da própria conta na nova tentativa | `shouldReturnSubWhenRetryGets409FromAccountCreatedByFirstAttempt` | devolve o `sub`, não 409; 2 POSTs | passou | ✅ |
| 18 | Conferência falha após timeout | `shouldNotPostAgainWhenTheCheckAfterTimeoutFails` | 503; 1 POST só | passou | ✅ |
| 19 | Conta órfã reaproveitada | `shouldReuseOrphanAccountWithSameUsernameAndUpdateItsData` (POST 409, busca acha a conta, sem registro local) | devolve o `sub`; PUT com o e-mail, o nome e `enabled: true` | passou | ✅ |
| 20 | Username de usuário cadastrado | `shouldThrowConflictWhenUsernameBelongsToRegisteredUser` | `UsuarioJaExisteException`; 1 POST, nenhum PUT | passou | ✅ |
| 21 | E-mail de outra conta | `shouldThrowConflictWhenEmailBelongsToAnotherAccount` (busca vazia) | `UsuarioJaExisteException`; nenhum PUT | passou | ✅ |
| 22 | Conta achada após timeout já é de usuário cadastrado | `shouldNotReuseAccountFoundAfterTimeoutWhenItHasLocalRecord` | `UsuarioJaExisteException`, não o `sub` dele; 1 POST, nenhum PUT | passou | ✅ |
| 23 | 409 e 503 em Problem Details | `GlobalExceptionHandlerTest`: `shouldReturn409WithConflictCode`, `shouldReturn503WithoutLeakingExternalServiceDetails` | 409 `USUARIO_JA_EXISTE`; 503 `SERVICO_INDISPONIVEL` com mensagem fixa | passou | ✅ |
| 24 | Seed do gestor após a V3 | `UsuarioTabelaTest.shouldFillInitialGestorWithKeycloakEmailAndNoClient` | `email = gestor@flowsla.local`, `cliente_id` nulo, `version = 0` | passou | ✅ |
| 25 | E-mail único sem diferenciar maiúsculas | `shouldRejectSameEmailIgnoringCase` (`ana.lima@…` e `Ana.Lima@…`) | `DuplicateKeyException` | passou | ✅ |
| 26 | E-mail obrigatório | `shouldRequireEmail` | erro de NOT NULL em `email` | passou | ✅ |
| 27 | Entidade × schema | `ddl-auto=validate` ao subir o contexto de todos os testes de integração | `Usuario` (com `email`, `clienteId`, `@Version`) bate com a V3 | contexto sobe | ✅ |

## Observações

1. **Achado (revisão da primeira versão):** um teste de latência falhava sempre (esperava "Keycloak" numa mensagem que não tinha a palavra). Com uma sonda, apareceu também o seguinte caso: timeout no POST, a busca falhava e o segundo POST recebia 409 da conta que a primeira tentativa gravou. O resultado era `UsuarioJaExisteException` para uma conta criada pelo próprio sistema. Os dois foram corrigidos (itens 17 e 18).
2. **Achado:** a busca com retry chamada dentro do laço de retry da criação multiplicava o tempo: 532 ms com read-timeout de 100 ms. Agora a criação usa a busca sem retry e faz no máximo 5 chamadas à Admin API (mais o pedido de token, se o cache estiver vazio).
3. **Achado:** no Boot 4, o starter de AOP se chama `spring-boot-starter-aspectj` (o `-aop` não existe), e o `RestClient.Builder` do Boot fica no `spring-boot-starter-restclient`, que não estava no projeto.
4. **Não verificado:** métricas do retry e do `RestClient` no `/actuator/prometheus`. Não subi o ambiente dev nesta task.
5. **Conta órfã:** se o timeout acontece e a conferência também falha, ou se a compensação da [autenticação - 04] não consegue remover a conta, sobra no Keycloak uma conta sem registro local. Nenhuma task do backlog cobria isso: a issue #27 trata só a falha do banco. Em 29/10/2026 ficou decidido tratar aqui. O `criarUsuario` reaproveita a conta com o mesmo username que não tem linha na tabela `usuario` (itens de conta órfã acima). Depende de a [autenticação - 04] gravar o registro local com o `sub` devolvido.
6. **Não verificado:** dois cadastros simultâneos do mesmo username, com a conta órfã ainda sem registro local, reaproveitariam a mesma conta. A trava fica na [autenticação - 04]: a tabela `usuario` tem `sub` único, então o segundo insert falha.
7. **V3 num banco já existente (dev):** o backend de dev em execução (devtools, código montado) reiniciou sozinho quando a migração foi criada e a aplicou em 29/10/2026 às 16:09, sobre V1 e V2 já aplicadas. Conferido com `\d usuario`: as 3 colunas, o índice `usuario_email_uk` em `lower(email)`, e o gestor com `gestor@flowsla.local` e `version = 0`. Efeito colateral: a V3 já está registrada no Flyway do dev. Se ela for editada antes do merge, o dev recusa subir (checksum); nesse caso, `make run-dev-down`.
8. **Achado:** no mesmo reinício automático, o backend de dev **não subiu** (`RestClient$Builder` não encontrado). O devtools reaproveita o classpath da JVM já em execução, que não tinha as dependências novas do `pom.xml`. Depois de reiniciar o container: `Successfully validated 3 migrations` (o checksum da V3 bate com o arquivo) e `/actuator/health` 200.
