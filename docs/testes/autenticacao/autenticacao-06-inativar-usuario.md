# Testes — [autenticação - 06] Inativar usuário

> Executados em 05/11/2026, na branch `feat/autenticacao-06` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto, com Testcontainers e `quay.io/keycloak/keycloak:26.4`. Verificação manual no dev, por HTTP, com token real do gestor (Authorization Code + PKCE).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 127 testes, 0 falhas (`InativacaoUsuarioTest` 10, `InativacaoUsuarioServiceTest` 7) | ✅ |
| 2 | Inativação completa | `shouldDisableAccountMarkInactiveAuditAndBlockValidToken` | 204; conta desabilitada no Keycloak real; `ativo` falso; auditoria com o `sub` do gestor; token válido recebe 401 `NAO_AUTENTICADO` | passou | ✅ |
| 3 | Idempotência | `shouldBeIdempotent` | 204 duas vezes; 1 chamada ao Keycloak; 1 auditoria | passou | ✅ |
| 4 | Sem substituto | `shouldRejectTechnicianWithOpenTicketsWithoutSubstituteBeforeKeycloak`, `shouldRequireSubstituteBeforeCallingKeycloak` | 422 `SUBSTITUTO_OBRIGATORIO`; Keycloak não chamado | passou | ✅ |
| 5 | Substituto inválido | `shouldRejectInvalidSubstitutes` (inativo, o próprio, gestor, inexistente) | 422 `SUBSTITUTO_INVALIDO`; Keycloak não chamado | passou | ✅ |
| 6 | Autoinativação | `shouldRejectManagerInactivatingThemselves` | 422 `AUTOINATIVACAO_NAO_PERMITIDA` | passou | ✅ |
| 7 | Último gestor | `shouldRejectLastActiveManagerBeforeCallingKeycloak` (unitário) | 422 `ULTIMO_GESTOR_ATIVO`; Keycloak não chamado | passou | ✅ |
| 8 | Transferência | `shouldTransferTicketsToSubstitute` (porta simulada) | `transferir` chamado com os subs e o gestor; técnico inativo, substituto ativo | passou | ✅ |
| 9 | Compensação | `shouldReenableAccountWhenLocalTransactionFails` (falha simulada na transferência) | 500; conta reabilitada no Keycloak; registro ativo; nenhuma auditoria | passou | ✅ |
| 10 | Concorrência | `shouldNotInactivateTechniciansThatAreSubstitutesOfEachOtherConcurrently` (duas requisições simultâneas, sincronizadas depois da validação) | 204 e 422; um técnico ativo e habilitado | passou em 3 de 3 rodadas; com `open-in-view=true`, falha com `[204, 204]` (prova feita) | ✅ |
| 11 | Revalidação na transação | `shouldRecheckLastManagerInsideTransactionAndReenableAccount`, `shouldRecheckOpenTicketsInsideTransaction` | falha depois do Keycloak; conta reabilitada; sem auditoria | passou | ✅ |
| 12 | Compensação com guarda | `shouldNotReenableAccountWhenRecordIsAlreadyInactive`, `shouldKeepOriginalFailureWhenReenablingFails` | não reabilita registro já inativo; falha original preservada | passou | ✅ |
| 13 | Sem módulo chamado | `shouldInactivateTechnicianWhileChamadoModuleDoesNotExist` | técnico inativado sem a porta | passou | ✅ |
| 14 | Cliente inativado | `shouldInactivateCompanyUsersWhenClientIsInactivated` | os dois usuários inativos e desabilitados; auditoria com `ator_sub` nulo | passou | ✅ |
| 15 | Só gestor | `AcessoPorRotaTest` com a rota nova | 403 `ACESSO_NEGADO` para técnico e cliente, com corpo `{}` e `{` | passou | ✅ |
| 16 | Contrato | `OpenApiContratoTest` | `docs/openapi.json` igual ao gerado | passou depois de regenerar o arquivo | ✅ |
| 17 | Dev: fluxo com token real | Técnico descartável: autoinativação, substituto igual, inativar duas vezes | 422, 422, 204, 204; Keycloak `enabled = false`; `ativo = f`; 1 linha `INATIVACAO_USUARIO`; `/api/tecnicos` com `id` | conforme o esperado | ✅ |

## Observações

1. **Achado (teste de concorrência):** com `open-in-view` ligado, a revalidação na transação lia o usuário do cache da sessão aberta na requisição, e os dois técnicos ficavam inativos. Por isso a propriedade foi desligada.
2. **Não verificado:** login por senha de um usuário inativado. O teste confirma a conta com `enabled = false` no Keycloak real e o 401 com token válido, mas ninguém tentou entrar pela tela.
3. **Não verificado:** falha na própria reabilitação contra o Keycloak real. Só o caminho unitário foi coberto: o erro vai para o log, e a conta fica desabilitada com registro ativo.
4. **Não verificado no dev:** rejeições que dependem de chamados, porque o módulo chamado não existe. `SUBSTITUTO_OBRIGATORIO`, transferência e compensação usam a porta simulada. `ULTIMO_GESTOR_ATIVO` tem só teste unitário: o gestor que chama está ativo, então a regra só dispara em corrida ou com perfil local diferente do token.
5. **Não verificado:** o plano manual pelo Swagger, seção 7A; as chamadas do item 17 foram feitas por HTTP. A inativação de cliente no dev também não foi executada.
6. **Pendente:** com falha em um usuário no ouvinte de `ClienteInativado`, ele continua ativo, e só o log avisa.
7. **Dado deixado no dev:** o técnico `Descartável QA` (`descartavel.<timestamp>@flowsla.local`), inativo, com 2 linhas imutáveis na auditoria.

## Revisão (branch `fix/autenticacao-06-revisao`)

> Executados em 08/11/2026, com as correções da revisão ainda não commitadas. Suíte e testes de mutação numa cópia do projeto, com Testcontainers. Verificação manual no dev, por HTTP, com token real do gestor.

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| R1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 132 testes, 0 falhas; `docs/openapi.json` sem mudança | ✅ |
| R2 | Teste unitário no CI | `./mvnw test -Dtest="com/gestaosla/backend/unit/**"` (o filtro do `make unit-test`) | `InativacaoUsuarioServiceTest` e `SegurancaArquiteturaTest` entre as classes | 9 classes, as duas presentes; antes da revisão, `InativacaoUsuarioServiceTest` ficava de fora | ✅ |
| R3 | Ponto 6, teste antes da correção | `shouldInactivateLocallyWhenKeycloakAccountNoLongerExists` contra o código antigo | falhar | `Status expected:<204> but was:<404>` (`RECURSO_NAO_ENCONTRADO`); depois da correção, passou | ✅ |
| R4 | Ouvinte assíncrono | `shouldInactivateCompanyUsersWhenClientIsInactivated` (Awaitility; thread do Keycloak diferente da do teste) | usuários inativos e desabilitados; Keycloak fora da thread da requisição | passou | ✅ |
| R5 | Conexão presa | Sonda temporária na cópia: conexões ativas no Hikari durante cada `desabilitar` | 0 (antes: 1) | `conexoes ativas=0 thread=task-1` nas duas chamadas (ver obs. R1) | ✅ |
| R6 | Desempate | `shouldNotRepeatOrSkipUsersWithSameNameAcrossPages` (30 nomes iguais, 7 por página) | os 30, sem repetir | passou | ✅ |
| R7 | Carga pela porta | `shouldListActiveTechniciansWithLoadFromTicketsPort` | `chamadosNaoFinalizados: 3` vindo da porta | passou | ✅ |
| R8 | Testes de mutação | 12 mudanças, uma por vez, numa cópia; testes de inativação, consulta, rota e segurança | cada uma derruba um teste | 11 pegas (tabela abaixo); 1 escapou, como previsto | ✅ |
| R9 | Dev: conta apagada no Keycloak | Técnico descartável; conta apagada pelo admin do console; `POST /api/usuarios/{id}/inativacao` | 204; registro inativo; aviso no log | 204; `ativo = f`; `INATIVACAO_USUARIO`; log "não existe no Keycloak" | ✅ |
| R10 | Dev: cliente inativado | Cliente descartável com 2 usuários; `POST /api/clientes/{id}/inativacao` | resposta rápida; usuários inativos e desabilitados; auditoria do sistema | 204 em 47 ms; os 2 com `ativo = f` e `enabled = false`; `INATIVACAO_USUARIO` com ator `SISTEMA` | ✅ |

**Testes de mutação (R8):**

| Mudança | Teste que falhou |
| --- | --- |
| `@Async` removido do ouvinte | `shouldInactivateCompanyUsersWhenClientIsInactivated` |
| `@EnableAsync` removido | `shouldInactivateCompanyUsersWhenClientIsInactivated` |
| 404 do Keycloak volta a interromper | `shouldInactivateLocallyWhenKeycloakAccountNoLongerExists` |
| Desempate removido | `shouldNotRepeatOrSkipUsersWithSameNameAcrossPages` |
| Carga fixa em 0 | `shouldListActiveTechniciansWithLoadFromTicketsPort` |
| `@PreAuthorize` da inativação removido | `shouldDeclareRoleOnEveryEndpointExceptCurrentUser` |
| Revalidação de "já inativo" na transação removida | `shouldSkipWhenAnotherInactivationCommittedWhileWaitingForLock` |
| `substitutoId` fora da auditoria | `shouldTransferTicketsToSubstitute` |
| Transferência para quem não é técnico | `shouldNotTransferTicketsWhenInactivatedUserIsNotTechnician` |
| Compensação removida | `shouldReenableAccountWhenLocalTransactionFails` e outros 3 |
| Trava removida | `shouldNotInactivateTechniciansThatAreSubstitutesOfEachOtherConcurrently` e outros 2 |
| Reabilitar mesmo sem conta no Keycloak | **nenhum** (ver obs. R2) |

### Observações da revisão

1. **R1 — sonda:** a execução da sonda terminou com exit 1. A causa provável é a própria sonda: o `doAnswer` dela, posto logo antes da requisição, substitui o do teste, que guarda a thread do Keycloak. Com isso a thread fica nula e a asserção do teste falha. A causa não foi conferida rodando de novo. As medidas impressas valem, e o teste sem a sonda passou na suíte (R1 e R4).
2. **R2 — lacuna conhecida:** reabilitar uma conta que não existe no Keycloak não é pego por nenhum teste. O efeito seria um log de erro falso na compensação ("conta ficou desabilitada"), sem mudar dado.
3. **Limite aceito:** o evento `ClienteInativado` não é persistido. Se a aplicação cair durante a inativação dos usuários, o que faltou continua ativo. O desligamento normal espera as tarefas (até 60 s).
4. **Não verificado:** o desligamento normal com tarefa em andamento, ou seja, se `await-termination` de fato segura o encerramento.
5. **Dados deixados no dev:** cliente `Descartável QA 1794137616` (inativo); usuários `descartavel.cli1`, `descartavel.cli2` e `descartavel.semconta` `.1794137616@flowsla.local`, inativos; a conta `semconta` foi apagada do Keycloak; as linhas da auditoria são imutáveis.
