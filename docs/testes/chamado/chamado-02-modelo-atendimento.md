# Testes — [chamado - 02] Criar o modelo de atendimento

> Executados em 14/11/2026, na branch `feat/chamados` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 330 testes, 0 falhas (ver [chamado - 01]) | ✅ |
| 2 | Atendimento nasce PENDENTE | `AtendimentoTest.shouldStartPending` | PENDENTE, sem `encerrado_em` | passou | ✅ |
| 3 | Interromper o corrente | `shouldInterruptCurrentAttendance` (PENDENTE e EM_ANDAMENTO) | INTERROMPIDO, com `encerrado_em` | passou | ✅ |
| 4 | Não interrompe encerrado | `shouldNotInterruptFinishedAttendance` (CONCLUIDO e INTERROMPIDO) | `IllegalStateException`; estado mantido | passou | ✅ |
| 5 | Um corrente por chamado | `AtendimentoChamadoTest.shouldAllowAtMostOneCurrentAttendancePerChamado` (pelo serviço e por SQL direto) | segundo PENDENTE e um EM_ANDAMENTO barrados pelo índice | passou | ✅ |
| 6 | Reaberto duas vezes = três atendimentos | `shouldKeepEveryAttendanceOfReopenedChamado` | INTERROMPIDO, INTERROMPIDO, PENDENTE; `correnteDe` devolve o terceiro | passou | ✅ |
| 7 | Interromper preserva | `shouldPreserveInterruptedAttendance` | a linha continua, INTERROMPIDO com `encerrado_em`; sem corrente | passou | ✅ |
| 8 | Interromper sem corrente | `shouldIgnoreInterruptionWithoutCurrentAttendance` | nada acontece | passou | ✅ |
| 9 | Transação obrigatória | `shouldRequireCallerTransaction` | `IllegalTransactionStateException` | passou | ✅ |
| 10 | Quebra: índice comum no lugar do parcial único | cópia, `-Dtest=AtendimentoChamadoTest` | falha | teste 5 falhou | ✅ |
| 11 | Quebra: interromper apaga o atendimento | idem | falha | testes 6 e 7 falharam | ✅ |
| 12 | Dev: migração V13 | stack de dev a partir da cópia (`-p flowsla-verificacao`) | V13 aplicada; índice `atendimento_corrente_uk` único e parcial | V13 com sucesso; índice `UNIQUE ... WHERE estado IN ('PENDENTE','EM_ANDAMENTO')` | ✅ |

## Observações

1. **Não verificável ainda:** a preservação das evidências em si. A tabela de evidências é do módulo evidencia, que não existe; o teste prova que o atendimento, a que elas vão pertencer, nunca é apagado.
2. **Não verificado:** a reabertura real. A task de reabertura ainda não existe; o teste reproduz a sequência criar → interromper → criar → interromper → criar pelo serviço.
