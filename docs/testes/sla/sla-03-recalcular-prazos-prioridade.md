# Testes — [sla - 03] Recalcular prazos quando a prioridade muda

> Executados em 14/11/2026, na branch `feat/SLA` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`) e `RelogioMutavel` controlando a hora. As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 273 testes, 0 falhas (as tasks [sla - 03] a [sla - 05] somam 23 testes novos aos 250 anteriores). Na primeira rodada, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Recálculo de relógio correndo (unitário) | `RelogioSlaTest.shouldRecalculateRunningClock` | prazo e vencimento novos; marco igual; CORRENDO | passou | ✅ |
| 3 | Prazo novo no passado (unitário) | `shouldViolateRightAwayWhenRecalculatedDeadlineIsPast` | violação com o vencimento novo | passou | ✅ |
| 4 | Cumprido e violado não mudam (unitário) | `shouldRefuseToRecalculateFulfilledOrViolatedClock` | `IllegalStateException`; `violado_em` intacto | passou | ✅ |
| 5 | Só os que correm, do próprio marco | `RelogiosSlaTest.shouldRecalculateOnlyRunningClocksFromTheirStartMark` (NORMAL → ALTA na segunda 11:00, RESPOSTA já cumprida) | RESPOSTA intacta (540, segunda 16:30); CAMPO 540 → segunda 16:30; RESOLUCAO 1080 → terça 16:30; nenhuma violação | passou | ✅ |
| 6 | Prazo novo no passado | `shouldViolateRightAwayWhenNewDeadlineIsAlreadyPast` (NORMAL → ALTA na terça 10:00) | RESPOSTA violada às 09:30 de segunda e CAMPO às 16:30, devolvidas; RESOLUCAO correndo | passou | ✅ |
| 7 | Violado não muda | `shouldNotRecalculateViolatedClock` | RESPOSTA continua com 120 e violada às 09:30 | passou | ✅ |
| 8 | Versão do chamador | `shouldRecalculateWithVersionGivenByCaller` | prazos da versão passada (60/120/180), não da política vigente | passou | ✅ |
| 9 | Marco da reabertura | `shouldRecalculateFromReopeningMark` | CAMPO e RESOLUCAO contados de quarta 10:00 (quinta e sexta 10:00) | passou (ver obs. 1) | ✅ |
| 10 | Quebra: recálculo toca relógio cumprido | cópia, `-Dtest=RelogiosSlaTest` | falha | testes 5 e 9 falharam | ✅ |
| 11 | Quebra: recálculo a partir do marco do ciclo 1 | idem | falha | teste 9 falhou | ✅ |
| 12 | Quebra: prazo vencido no recálculo não viola | cópia, `-Dtest=RelogioSlaTest,RelogiosSlaTest` | falha | testes 3 e 6 falharam | ✅ |

## Observações

1. **Achado (no teste):** a primeira versão do teste 9 falhou porque a RESPOSTA nunca tinha sido cumprida. Ela ainda corria, com o prazo vencido e sem marcação, e foi recalculada e violada. É o comportamento correto. O teste passou a cumprir a RESPOSTA para isolar o que quer provar.
2. **Comportamento:** um relógio vencido que a rotina de 15 minutos ainda não marcou é tratado como correndo. Se o prazo novo também já passou, a violação é marcada com o vencimento novo.
3. **Não verificado:** recálculos simultâneos do mesmo chamado. O `@Version` deve fazer o segundo falhar com conflito; não há teste concorrente.
