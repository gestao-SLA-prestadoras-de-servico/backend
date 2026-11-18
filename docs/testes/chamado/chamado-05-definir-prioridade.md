# Testes — [chamado - 05] Definir ou alterar a prioridade

> Executados em 14/11/2026, na branch `feat/chamados` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 360 testes, 0 falhas (ver [chamado - 03]) | ✅ |
| 2 | Antes de RESOLVIDO | `PrioridadeChamadoTest.shouldChangePriorityBeforeResolved` (ABERTO, ATRIBUIDO, EM_ATENDIMENTO) | 200; prioridade e prioridade definida ALTA | passou | ✅ |
| 3 | A partir de RESOLVIDO | `shouldRejectPriorityChangeFromResolvedOn` (RESOLVIDO, ENCERRADO, CANCELADO) | 422 `TRANSICAO_INVALIDA`; prioridade e prazos intactos | passou | ✅ |
| 4 | Só os relógios correndo | `shouldRecalculateOnlyRunningClocks` (resposta cumprida, NORMAL → ALTA) | resposta continua 540 / terça 09:00; campo 540 / terça 09:00; resolução 1080 / quarta 09:00 | passou | ✅ |
| 5 | Violado continua violado | `shouldKeepViolatedClockViolated` | resposta VIOLADO, com vencimento e `violado_em` originais | passou | ✅ |
| 6 | Recálculo vence na hora | `shouldWriteViolationNoticeWhenRecalculatedDeadlineAlreadyPassed` (12:00; ALTA venceria às 11:00) | resposta VIOLADO, `comViolacao: true`; 1 `VIOLACAO` na outbox com o `eventoId` determinístico | passou | ✅ |
| 7 | Mesma prioridade | `shouldIgnoreSamePriority` (NORMAL igual à padrão, depois ALTA duas vezes) | só a primeira ALTA altera: `version` +1 e 1 auditoria | passou | ✅ |
| 8 | Auditoria e histórico | `shouldAuditPreviousAndNewPriority` | `{NORMAL → ALTA}` e `{ALTA → NORMAL}` com o sub do gestor; histórico invisível ao cliente e ao técnico | passou | ✅ |
| 9 | Papéis e erros | `shouldAllowOnlyManager` e `AcessoPorRotaTest` | 403 para cliente e técnico, antes de validar o corpo; 404 para chamado inexistente; 400 sem prioridade | passou | ✅ |
| 10 | Quebra: violação não repassada ao notificador | cópia, `-Dtest=PrioridadeChamadoTest` | falha | teste 6 falhou (nenhum evento na outbox) | ✅ |
| 11 | Quebra: RESOLVIDO aceito | idem | falha | teste 3: 422 esperado, veio 200 | ✅ |
| 12 | Quebra: mesma prioridade grava e audita | idem | falha | teste 7 falhou | ✅ |

## Observações

1. **Decisão do usuário aplicada:** a prioridade só muda antes de RESOLVIDO. O critério "qualquer estado não final" foi ajustado por essa decisão.
2. **Não verificado:** duas alterações simultâneas no mesmo chamado. O `@Version` deve fazer a segunda falhar com 409; não há teste concorrente.
3. **Não verificado no dev:** o endpoint com token real (o realm não permite login direto). O roteiro está na seção 7D do `docs/plano-testes-manuais.md`.
