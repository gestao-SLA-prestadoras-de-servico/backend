# Testes — [autenticação - 05] Consultar usuários, técnicos e o perfil corrente

> Executados em 05/11/2026, na branch `feat/autenticacao-05` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto, com Testcontainers. Verificação manual no dev, com tokens reais obtidos por Authorization Code + PKCE no client `flowsla-swagger`.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 110 testes, 0 falhas, depois da correção da obs. 1 | ✅ |
| 2 | `/me` do cliente | `shouldReturnProfileAndCompanyOfCurrentClient` | `sub`, `nome`, `perfil` e `clienteId` | passou | ✅ |
| 3 | Filtros de usuários | `shouldFilterUsersByCompanyAndActive`, `shouldFilterUsersByProfile` | só os usuários do filtro; inativos incluídos sem o filtro `ativo` | passou | ✅ |
| 4 | Paginação de usuários | `shouldPaginateUsers` (3 usuários, `size=2`) | 2 itens, 3 no total, 2 páginas | passou | ✅ |
| 5 | Perfil inválido | `shouldReturn400ForUnknownProfile` | 400 `VALIDACAO` | passou | ✅ |
| 6 | Técnicos | `shouldListActiveTechniciansWithLoadByDefault` | ativos com carga 0, com `ativos=true` e sem o parâmetro; `ativos=false` traz os inativos; gestor fora | passou | ✅ |
| 7 | Gestores ativos | `shouldListOnlyActiveManagers` | inclui o gestor inicial e um ativo; exclui gestor inativo e técnico | passou | ✅ |
| 8 | Só gestor | `AcessoPorRotaTest` com `GET /api/usuarios` e `GET /api/tecnicos` | 403 `ACESSO_NEGADO` para técnico e cliente; `/me` aberto | passou | ✅ |
| 9 | Contrato | `OpenApiContratoTest` | `docs/openapi.json` igual ao gerado; 401, 403 e 500 em toda operação | passou depois de regenerar o arquivo | ✅ |
| 10 | Módulos | `ModularidadeTest` | sem violação | passou | ✅ |
| 11 | Dev: gestor com token real | `/api/usuarios/me`; `/api/usuarios?size=2`; `?perfil=GESTOR&ativo=true`; `/api/tecnicos?ativos=true` | 200 com os dados do dev; técnicos com carga 0 | conforme o esperado | ✅ |
| 12 | Dev: entradas inválidas | `?perfil=ADMIN`; `?clienteId=abc`; `/api/tecnicos?sort=senha` | 400 `VALIDACAO` | conforme o esperado | ✅ |
| 13 | Dev: técnica e cliente com token real | Ana e Carla em `/api/usuarios/me`, `/api/usuarios` e `/api/tecnicos` | 200 no `/me` (Carla com `clienteId`); 403 `ACESSO_NEGADO` nos outros | conforme o esperado | ✅ |
| 14 | Timeout no meio do corpo | `shouldRetryAndReturn503WhenTimeoutHitsWhileReadingTheBody` (cabeçalhos a tempo, corpo atrasado) | `IdpIndisponivelException`, 3 tentativas | sem a correção, falha com `IllegalStateException`; com ela, passou | ✅ |
| 15 | Resposta inválida | `shouldNotTreatMalformedResponseAsUnavailable` | `IllegalStateException`, 1 tentativa | passou | ✅ |
| 16 | Intermitência | `KeycloakAdminAdapterTest` isolado, 20 vezes | 20 rodadas sem falha | 20 de 20; antes falhava em cerca de 1 a cada 3 | ✅ |

## Observações

1. **Achado (bug anterior a esta task, corrigido):** `KeycloakAdminAdapterTest.shouldThrowConflictWhenEmailBelongsToAnotherAccount` falhava de forma intermitente com `IllegalStateException`. Eram dois problemas:
   - **Produção:** o read-timeout do `JdkClientHttpRequest` vale até o corpo ser lido. Se os cabeçalhos chegam a tempo e o corpo atrasa, ele fecha o stream, e a leitura falha com `IOException: closed` dentro de uma `RestClientException` genérica. O `traduzir` só reconhecia `ResourceAccessException`, então Keycloak lento virava 500 de "erro de configuração", sem retry, em vez de 503 (RN-137). Agora a origem `IOException` também é indisponibilidade.
   - **Teste:** a primeira chamada da JVM levava de 175 a 200 ms (carga de classes do HttpClient e do Jackson), contra um timeout de 150 ms no teste. As seguintes levavam cerca de 5 ms. Falhava só o primeiro teste da classe. Um `@BeforeAll` agora aquece a JVM com timeout longo.
2. **Rodadas da suíte antes da correção:** das 4 rodadas, a primeira falhou só porque `OpenApiContratoTest` regenerou o contrato, como previsto; a segunda, pelo item acima; as duas últimas passaram.
3. **Não verificado no dev:** o filtro `clienteId` com um id válido e `ativos=false`. Os dois têm cobertura de integração (itens 3 e 6). O plano de testes manuais (passos 7.8 a 7.12) não foi executado pelo Swagger; as chamadas equivalentes dos itens 11 a 13 foram feitas por HTTP.
4. **Pendente:** `chamadosNaoFinalizados` é `0` fixo até o módulo chamado existir.
5. **Sem consumidor ainda:** `listarGestoresAtivos()` só é exercitado pelo teste, até a task de notificação de SLA usá-lo.
