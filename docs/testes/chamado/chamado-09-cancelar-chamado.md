# Testes — [chamado - 09] Cancelar chamado

> Executados em 16/11/2026, na branch `feat/chamados_6-10` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 410 testes, 0 falhas (ver [chamado - 08]) | ✅ |
| 2 | Cancelar antes da resolução | `CancelamentoChamadoTest.shouldCancelBeforeResolution` (ABERTO, ATRIBUIDO, EM_ATENDIMENTO) | 200 CANCELADO com `estadoFinalEm`; motivo gravado; auditoria `CANCELAMENTO` com o motivo e `MUDANCA_ESTADO` para CANCELADO; nenhum evento na outbox | passou | ✅ |
| 3 | Atendimento interrompido | `shouldInterruptCurrentAttendance` | atendimento EM_ANDAMENTO vira INTERROMPIDO | passou | ✅ |
| 4 | RESOLVIDO e depois | `shouldRejectCancellationFromResolvedOn` (RESOLVIDO, ENCERRADO, CANCELADO) | 422 `CANCELAMENTO_NAO_PERMITIDO`; estado mantido | passou | ✅ |
| 5 | Sem apuração | `shouldHaveNoSlaAssessment` | `/sla` → `apurado: false` | passou | ✅ |
| 6 | Motivo, papéis e 404 | `shouldRequireReasonAndManager` e `AcessoPorRotaTest` | motivo em branco → 400; cliente e técnico → 403; chamado inexistente → 404 | passou | ✅ |
| 7 | Quebra: RESOLVIDO aceito no serviço | cópia, `-Dtest=CancelamentoChamadoTest` | falha | teste 4: `CANCELAMENTO_NAO_PERMITIDO` esperado, veio `TRANSICAO_INVALIDA` | ✅ |
| 8 | Quebra: cancelamento sem interromper o atendimento | idem | falha | teste 3 falhou | ✅ |

## Observações

1. **Achado:** na quebra do item 7, a máquina de estados barrou RESOLVIDO → CANCELADO sozinha (RN-10), mas com o código genérico. A checagem do serviço é a que dá o código certo da RN-19.
2. **Não verificável nesta task:** "a rotina de violação ignora o chamado depois do cancelamento". A rotina é a [chamado - 14]; por decisão do usuário, ela vai filtrar pelo estado do chamado. Os relógios do cancelado continuam ativos em `relogio_sla`.
3. **Decisão registrada:** o técnico não é avisado do cancelamento (teste 2 confere que nenhum evento é gravado).
4. **Não verificado no dev:** o endpoint com token real. O roteiro está na seção 7F do `docs/plano-testes-manuais.md`.
