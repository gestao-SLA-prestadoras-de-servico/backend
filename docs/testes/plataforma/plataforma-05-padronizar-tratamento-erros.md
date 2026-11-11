# Testes — [plataforma - 05] Padronizar o tratamento de erros

> Executados em 22/10/2026, na branch `feat/plataforma` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). Rodados numa cópia do projeto porque o `target/maven-status` do repositório pertence ao root (ver plataforma-04).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | `GlobalExceptionHandlerTest` 10, `ModularidadeTest` 2, `RelogioArquiteturaTest` 1, `BackendApplicationTests` 6; 0 falhas | ✅ |
| 2 | Validação | `shouldReturn400WithFieldErrorsOnValidation` | 400, `VALIDACAO`, `erros[0].campo = titulo` | passou | ✅ |
| 3 | JSON malformado | `shouldReturn400OnMalformedBody` | 400, `VALIDACAO` | passou | ✅ |
| 4 | Não autenticado (lançado no MVC) | `shouldReturn401WhenNotAuthenticated` | 401, `NAO_AUTENTICADO` | passou | ✅ |
| 5 | Não autenticado (filtro de segurança, app real) | `BackendApplicationTests.shouldReturnProblemDetailsWhenNotAuthenticated` | 401, `application/problem+json`, `NAO_AUTENTICADO` | passou | ✅ |
| 6 | Sem permissão | `shouldReturn403WhenAccessDenied` | 403, `ACESSO_NEGADO` | passou | ✅ |
| 7 | Recurso de outro cliente | `shouldReturn404WithoutRevealingResourceOfAnotherClient` | 404, `RECURSO_NAO_ENCONTRADO`, detail genérico | passou | ✅ |
| 8 | Rota inexistente | `shouldReturn404OnUnknownRoute` | 404, `RECURSO_NAO_ENCONTRADO` | passou (após correção, ver obs. 1) | ✅ |
| 9 | Conflito de versão | `shouldReturn409WithClearMessageOnOptimisticLock` | 409, `CONFLITO_VERSAO`, mensagem clara | passou | ✅ |
| 10 | Regra de negócio | `shouldReturn422WithBusinessCode` | 422, `TRANSICAO_INVALIDA`, detail da exceção | passou | ✅ |
| 11 | Método não suportado | `shouldReturn405AsInvalidRequest` | 405, `REQUISICAO_INVALIDA` | passou (após correção, ver obs. 1) | ✅ |
| 12 | Erro inesperado | `shouldReturn500WithoutLeakingInternalMessage` | 500, `ERRO_INTERNO`, sem a mensagem original | passou | ✅ |
| 13 | JPA lança a exceção mapeada para 409 | Entidade temporária com `@Version`; duas cópias carregadas, a segunda salva desatualizada | `ObjectOptimisticLockingFailureException` | `ObjectOptimisticLockingFailureException` | ✅ |

A entidade, o repositório, a migração e o teste temporários do item 13 foram criados numa cópia do projeto e descartados.

## Observações

1. **Achado:** na primeira execução, 404 de rota e 405 saíam como Problem Details mas **sem** `codigo`. Para esses erros o Spring chama `handleExceptionInternal` com `body` nulo e só a superclasse cria o `ProblemDetail`. Confirmado também na aplicação real (`@WithMockUser`, `GET /rota-inexistente` e `POST /actuator/health`). Corrigido acrescentando o `codigo` depois de chamar a superclasse.
2. **Não verificado:** o 403 vindo do **filtro** de segurança para usuário autenticado sem papel. Ainda não existe regra de papel nem endpoint; usa o mesmo caminho do 401 (item 5), que foi verificado. Deve ser coberto na task de autenticação.
3. **Fora do escopo:** erros que não passam pelo `DispatcherServlet` (ex.: exceção em filtro que não seja de segurança) continuam no `/error` padrão do Spring Boot.
