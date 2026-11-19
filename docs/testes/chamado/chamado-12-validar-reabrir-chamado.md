# Testes — [chamado - 12] Validar ou reabrir o chamado (cliente)

> Executados em 16/11/2026, na branch `feat/chamados11-15` (alterações ainda não commitadas), com Testcontainers. As quebras propositais foram feitas numa cópia do projeto, fora do repositório. A verificação no dev usou o ambiente de `make ci-run-dev`, com o app reiniciado.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 510 testes, 0 falhas (ver chamado-11) | ✅ |
| 2 | Validação | `ValidacaoReaberturaTest.shouldCloseTicketFreezingAssessment` | ENCERRADO; `estadoFinalEm` = hora do Clock; 3 linhas em `apuracao_sla`; `MUDANCA_ESTADO` com o cliente como ator | passou | ✅ |
| 3 | Outra empresa | `shouldReturn404ToClientOfAnotherCompany` | 404 na validação e na reabertura; estado mantido | passou | ✅ |
| 4 | Papéis | `shouldForbidManagerAndTechnician` e `AcessoPorRotaTest` | gestor e técnico recebem 403 antes de validar o corpo | passou | ✅ |
| 5 | Validação fora de RESOLVIDO | `shouldRejectValidationOutsideResolved` | 422 `TRANSICAO_INVALIDA` | passou | ✅ |
| 6 | Reabertura | `shouldReopenToSameTechnicianRestartingFieldAndResolutionClocks` | ATRIBUIDO; mesmo técnico; `resolvido_em` e `aviso_enviado_em` nulos; atendimentos CONCLUIDO e PENDENTE; ativos RESPOSTA:1, CAMPO:2 e RESOLUCAO:2; o ciclo 2 começa na reabertura e RESPOSTA continua na abertura; REABERTURA para o técnico | passou | ✅ |
| 7 | Auditoria da reabertura | `shouldAuditReopening` | `ator_sub` e `perfil` do cliente, `entidade_id`, `ocorrido_em`, justificativa, `de` e `para`; histórico visível ao cliente com a justificativa | passou | ✅ |
| 8 | Reabrir ENCERRADO | `shouldRejectReopeningClosedTicket` | 422 `REABERTURA_NAO_PERMITIDA` | passou | ✅ |
| 9 | Justificativa vazia | `shouldRequireJustification` | 400 `VALIDACAO` | passou | ✅ |
| 10 | Versão desatualizada | `shouldReturn409ForStaleVersion` | 409 `CONFLITO_VERSAO` nas duas rotas; nada muda | passou | ✅ |
| 11 | Validação e reabertura simultâneas | `shouldLetOnlyOneOfConcurrentValidationAndReopeningWin` (2 threads, mesma versão) | status {200, 409} | passou | ✅ |
| 12 | Quebra: reabertura não limpa `resolvidoEm` | cópia, as três classes de teste novas | falha | item 6 falhou | ✅ |
| 13 | Quebra: reabertura não limpa `avisoEnviadoEm` | idem | falha | item 6 falhou | ✅ |
| 14 | Quebra: sem conferir a `version` | idem | falha | item 10 falhou | ✅ |
| 15 | Quebra: reabertura sem `criarPendente` | idem | falha | item 6 falhou | ✅ |
| 16 | Quebra: sem o filtro de empresa | idem | falha | item 3 falhou | ✅ |
| 17 | Dev: roteiro 7K.5 a 7K.11 | `roteiro7k.sh` (fora do repositório) | `version` na visão do cliente; 404 para outra empresa; 409 com versão antiga; reabertura com ciclos 2 e o mesmo técnico; auditoria e REABERTURA; segunda conclusão; validação com apuração; 422 ao reabrir ENCERRADO | os 13 passos OK | ✅ |

## Observações

1. **Achado:** com a quebra 14 aplicada, o item 11 continuou passando. Na corrida exata, o `@Version` do chamado já barra a segunda gravação. A conferência explícita da `version` é o que garante 409, e não 422, para quem chega depois.
2. **Achado:** a visão do cliente não tinha `version`. Ela foi acrescentada, e o `openapi.json` mudou junto.
3. **Não verificado:** a concorrência com a rotina automática. A rotina é da [chamado - 15], e o teste fica lá.
4. **Não verificado:** a reabertura de um chamado cujo ciclo ainda corria com o prazo vencido. O `reiniciarParaReabertura` devolve a violação (sla-04) e ela vai para o `NotificadorViolacao`, mas não há teste neste fluxo.
