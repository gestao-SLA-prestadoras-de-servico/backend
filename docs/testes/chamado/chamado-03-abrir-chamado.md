# Testes — [chamado - 03] Abrir chamado

> Executados em 14/11/2026, na branch `feat/chamados` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 360 testes, 0 falhas (`AberturaChamadoTest` 10, `ConsultaChamadoTest` 9, `PrioridadeChamadoTest` 12, `AcessoPorRotaTest` +2; os 3 testes de endpoint do `ApuracaoSlaTest` passaram para o `ConsultaChamadoTest`). Na rodada anterior, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Abertura completa | `AberturaChamadoTest.shouldOpenChamadoWithNumberAndThreeDeadlines` (segunda 09:00, Padrão NORMAL) | 201 com `Location`, número, ABERTO, prioridade NORMAL; relógios com vencimento terça 09:00, quinta 09:00 e segunda seguinte 09:00, gravados em `relogio_sla`; versão e `aberto_em` gravados; 1 auditoria `ABERTURA_CHAMADO` e histórico `ABERTURA` | passou | ✅ |
| 3 | Contrato de outra empresa ou inexistente | `shouldAnswer404ForContractOfAnotherCompany` | 404 `RECURSO_NAO_ENCONTRADO`; nada gravado | passou | ✅ |
| 4 | Fora da vigência | `shouldRejectContractOutsideValidity` (vencido ontem e começando amanhã) | 422 `CONTRATO_FORA_VIGENCIA`; o último dia da vigência ainda aceita (201) | passou | ✅ |
| 5 | Sem política | `shouldRejectContractWithoutPolicy` | 422 `CONTRATO_SEM_POLITICA` | passou | ✅ |
| 6 | Sugerida ALTA | `shouldKeepSuggestedPriorityWithoutChangingDeadlines` | sugerida ALTA registrada; prioridade NORMAL; resposta 540 min, terça 09:00 | passou | ✅ |
| 7 | Fora da janela (RN-61) | `shouldStartCountingAtNextWindowOpening` (sábado 10:00) | `abertoEm` sábado 10:00; resposta vence segunda 17:00 | passou | ✅ |
| 8 | Falha no cálculo desfaz tudo (RN-111) | `shouldRollBackEverythingWhenDeadlineCalculationFails` (`CalendarioJanela.adicionarMinutos` lança exceção) | 500; nenhum chamado, auditoria ou histórico novos | passou | ✅ |
| 9 | Aberturas simultâneas | `shouldGiveUniqueIncreasingNumbersToConcurrentOpenings` (10 threads) | 10 × 201; números distintos, todos maiores que o maior anterior | passou | ✅ |
| 10 | Validação | `shouldValidateBody` | 400 `VALIDACAO` | passou | ✅ |
| 11 | Só CLIENTE abre | `shouldAllowOnlyClientToOpen` e `AcessoPorRotaTest.shouldDenyOpeningToNonClientsBeforeValidatingBody` | 403 para gestor e técnico, antes de validar o corpo | passou | ✅ |
| 12 | Quebra: sem o filtro de empresa no contrato | cópia, `-Dtest=AberturaChamadoTest` | falha | teste 3: 404 esperado, veio 201 | ✅ |
| 13 | Quebra: abertura sem `@Transactional` | idem | falha | 6 testes falharam, inclusive o 8 (o chamado ficou gravado sem os relógios) | ✅ |

## Observações

1. **Achado:** o `@MockitoSpyBean` no `RelogiosSla` não serve para simular a falha. O stubbing (`doThrow().when(spy)`) passa pelo proxy transacional, que exige transação (`MANDATORY`) e falha. A falha foi simulada no `CalendarioJanela`, que é o próprio cálculo dos prazos.
2. **Ajuste no teste:** "crescente" com aberturas simultâneas foi verificado como "maior que todos os números anteriores à rodada". Entre aberturas concorrentes não há ordem definida.
3. **Não verificado no dev:** o `POST` com token real. O realm não permite login direto; o roteiro está na seção 7D do `docs/plano-testes-manuais.md`. As migrações V12 e V13 foram verificadas no dev ([chamado - 01]).
