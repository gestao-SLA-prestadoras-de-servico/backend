# Testes — [sla - 04] Reiniciar relógios na reabertura

> Executados em 14/11/2026, na branch `feat/SLA` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 273 testes, 0 falhas (ver [sla - 03]) | ✅ |
| 2 | Encerrar ciclo cumprido (unitário) | `RelogioSlaTest.shouldCloseCycleKeepingHistory` | inativo, continua CUMPRIDO, sem violação | passou | ✅ |
| 3 | Encerrar ciclo vencido sem marcação (unitário) | `shouldMarkPendingViolationWhenClosingExpiredCycle` | violação marcada com o vencimento; no vencimento exato, não | passou | ✅ |
| 4 | Reinício e histórico | `RelogiosSlaTest.shouldRestartFieldAndResolutionClocksOnReopening` (ALTA, tudo cumprido, reabertura quarta 10:00) | RESPOSTA no ciclo 1, intacta; CAMPO e RESOLUCAO no ciclo 2 com marco na reabertura, vencendo quinta e sexta 10:00; histórico com 5 relógios, os do ciclo 1 inativos e com o cumprimento original | passou | ✅ |
| 5 | Violações de ciclos diferentes | `shouldTreatViolationsOfDifferentCyclesAsDifferentEvents` | ciclo 1 com marco na abertura; ciclo 2 com marco na reabertura e violação às 10:00 de quinta | passou | ✅ |
| 6 | Violação pendente do ciclo encerrado | `shouldReturnPendingViolationOfClosedCycle` (CAMPO nunca cumprido) | `Violacao` do CAMPO, ciclo 1, às 16:30 de segunda | passou | ✅ |
| 7 | Recálculo depois da reabertura | `shouldRecalculateFromReopeningMark` (ver [sla - 03]) | marco da reabertura | passou | ✅ |
| 8 | Quebra: reabertura reinicia também a RESPOSTA | cópia, `-Dtest=RelogiosSlaTest` | falha | testes 4, 6 e 7 falharam | ✅ |

## Observações

1. **Achado:** ao inativar o ciclo atual e inserir o novo na mesma transação, o Hibernate faria os INSERTs antes dos UPDATEs, e o índice único parcial (um ativo por tipo) barraria. O serviço força o `flush` da inativação antes de inserir.
2. **Decisão registrada:** a violação de um ciclo anterior conta na apuração final. Está testada no `ApuracaoSlaTest` ([sla - 05]).
3. **Não verificado:** reabrir duas vezes seguidas sem cumprir nada no meio. O código cria o ciclo 3 da mesma forma; não há teste específico.
