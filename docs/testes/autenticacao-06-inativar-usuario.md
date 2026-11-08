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
