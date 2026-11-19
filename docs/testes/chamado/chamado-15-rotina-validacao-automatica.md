# Testes — [chamado - 15] Rotina de validação automática e aviso prévio

> Executados em 17/11/2026, na branch `feat/chamados11-15` (alterações ainda não commitadas), com Testcontainers. As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 523 testes, 0 falhas (ver chamado-14) | ✅ |
| 2 | Segunda 10:00 com execução horária | `ValidacaoAutomaticaTest.shouldWarnOnWednesdayAndCloseOnThursdayRunningHourly` (de hora em hora, de segunda 11:00 a sexta 12:00) | `aviso_enviado_em` = quarta 10:00; `estado_final_em` = quinta 10:00; um só `AVISO_ENCERRAMENTO`, com o `eventoId` de chamado + `resolvido_em`, `encerramentoPrevistoEm` = quinta 10:00 e o e-mail da empresa | passou | ✅ |
| 3 | Encerramento | `shouldCloseFreezingAssessmentAndAuditAsSystem` | ENCERRADO; `estado_final_em`; 3 linhas em `apuracao_sla`; `VALIDACAO_AUTOMATICA` e `MUDANCA_ESTADO` sem ator | passou | ✅ |
| 4 | Antes dos marcos | `shouldDoNothingBeforeTheMarks` | 1 min antes do aviso, sem aviso; 1 min antes do limite, segue RESOLVIDO | passou | ✅ |
| 5 | Reabertura e nova resolução | `shouldSendNewWarningAfterReopeningAndNewResolution` | `aviso_enviado_em` limpo; dois `AVISO_ENCERRAMENTO`, com `eventoId` diferentes | passou | ✅ |
| 6 | Aviso atrasado | `shouldPostponeClosingWhenWarningIsLate` (primeira passada na quinta 11:00, depois do limite) | aviso na hora, com `encerramentoPrevistoEm` na sexta 11:00; na sexta 10:59 segue RESOLVIDO; na sexta 11:00 encerra | passou | ✅ |
| 7 | Chamado em uso pelo cliente | `shouldSkipLockedTicketAndCloseItInNextRun` (outra thread trava o chamado com `for update`) | a rotina não espera (limite de 30 s) e não encerra; liberada a trava, a passada seguinte encerra | passou | ✅ |
| 8 | Lotes | `shouldProcessAllTicketsInBatches` (lote 2, três vencidos e dois recentes) | os três avisados, os dois recentes não, sem laço infinito | passou | ✅ |
| 9 | Quebra: aviso a cada passada | cópia, `RotinaViolacaoTest` e `ValidacaoAutomaticaTest` | falha | itens 2, 3, 6 e 7 falharam | ✅ |
| 10 | Quebra: sem adiar o encerramento | idem | falha | item 6 falhou | ✅ |
| 11 | Quebra: `eventoId` sem `resolvido_em` | idem | falha | item 5 falhou | ✅ |
| 12 | Quebra: sem `SKIP LOCKED` | idem | falha | item 7 falhou | ✅ |
| 13 | Quebra: cursor que não avança | idem | falha | item 8 falhou (estourou o tempo) | ✅ |
| 14 | Quebra: sem a auditoria | idem | falha | item 3 falhou | ✅ |

## Observações

1. **Não verificado no dev:** o disparo pelo cron real e o e-mail de aviso no Mailpit, pelo mesmo motivo da chamado-14. O roteiro está na seção 7L do plano manual.
2. **Não verificado:** o cliente que leu a `version` antes do aviso recebe 409 ao validar, porque o aviso muda a versão do chamado. É o comportamento da chamado-12, sem teste próprio aqui.
3. **Não verificado:** um chamado RESOLVIDO sem os três relógios. O `congelarApuracao` lança `IllegalStateException`, o lote volta atrás e a rotina para, e isso se repete em toda passada enquanto o chamado existir: os chamados depois dele no cursor também ficam sem aviso e sem encerramento. Na aplicação, todo chamado ganha os relógios na abertura. A massa de teste por SQL deixa `resolvido_em` nulo, e esses chamados não entram na consulta.
