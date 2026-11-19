# Testes — [chamado - 11] Concluir atendimento

> Executados em 16/11/2026, na branch `feat/chamados11-15` (alterações ainda não commitadas), com Testcontainers. As quebras propositais foram feitas numa cópia do projeto, fora do repositório. A verificação no dev usou o ambiente de `make ci-run-dev`, com o app reiniciado.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 510 testes, 0 falhas, na segunda rodada (a primeira falhou só no `OpenApiContratoTest`, que regravou o `openapi.json` com as rotas novas) | ✅ |
| 2 | Conclusão com evidência RECEBIDA | `ConclusaoAtendimentoTest.shouldConcludeWithReceivedEvidenceResolvingTicketAndFulfillingResolutionClock` | RESOLVIDO; `resolvidoEm` = hora do Clock; RESOLUCAO CUMPRIDO; atendimento CONCLUIDO com `encerradoEm`; `MUDANCA_ESTADO` com o técnico como ator | passou | ✅ |
| 3 | E-mail RESOLUCAO | `shouldSendResolutionEmailToClient` | evento para o e-mail de contato da empresa | passou | ✅ |
| 4 | CONCLUSAO só com a preferência | `shouldNotifyOnlyManagersWithPreference` | destinatário só o gestor que ativou; outro gestor ativo fica de fora | passou | ✅ |
| 5 | Sem preferência | `shouldNotWriteConclusionEventWithoutPreference` | nenhum evento CONCLUSAO | passou | ✅ |
| 6 | Sem evidência | `shouldRejectWithoutEvidence` | 422 `EVIDENCIA_OBRIGATORIA`; chamado, atendimento e outbox intactos | passou | ✅ |
| 7 | Evidência de outro atendimento | `shouldIgnoreEvidenceOfAnotherAttendance` | 422 `EVIDENCIA_OBRIGATORIA` | passou | ✅ |
| 8 | Sem descrição técnica | `shouldRejectWithoutTechnicalDescription` | 422 `DESCRICAO_TECNICA_OBRIGATORIA`; nada muda | passou | ✅ |
| 9 | Outro técnico | `shouldReturn404ToAnotherTechnician` | 404; nada muda | passou | ✅ |
| 10 | Papéis | `shouldForbidManager` e `AcessoPorRotaTest` (rota na lista do técnico) | gestor e cliente recebem 403 antes de validar o corpo | passou | ✅ |
| 11 | Atendimento não iniciado | `shouldRejectWhenAttendanceIsNotInProgress` | 422 `TRANSICAO_INVALIDA` | passou | ✅ |
| 12 | Quebra: sem conferir a evidência | cópia, as três classes de teste novas | falha | itens 6 e 7 falharam | ✅ |
| 13 | Quebra: sem conferir a descrição | idem | falha | item 8 falhou | ✅ |
| 14 | Dev: roteiro 7K.1 a 7K.4 | `roteiro7k.sh` (fora do repositório), com chamados novos e tokens reais | 422 sem evidência; 422 sem descrição; 200 RESOLVIDO com CUMPRIDO e CONCLUIDO; RESOLUCAO para o e-mail da empresa; CONCLUSAO só para o gestor com preferência | os 7 passos OK | ✅ |

## Observações

1. **Achado:** a `version` não aparecia na visão do cliente, e a [chamado - 12] passou a exigi-la. Foi acrescentada lá.
2. **Não verificado:** conclusão atrasada violando o relógio de resolução. O caminho é o mesmo do início atrasado (`cumprir` devolve a violação ao `NotificadorViolacao`), testado na chamado-10, mas não há teste específico aqui.
3. **Não verificado:** a corrida entre o envio de uma evidência e a conclusão. Se a conclusão terminar primeiro, a recepção refeita dentro da transação não acha mais atendimento EM_ANDAMENTO e responde 422. Se o envio terminar primeiro, a evidência entra no atendimento antes da conclusão. O caso de o envio ler o atendimento antes e gravar depois da conclusão não tem teste; nele, a foto ficaria ligada ao atendimento já CONCLUIDO.
4. **Gestor inativo** com a preferência gravada não recebe CONCLUSAO. Esse filtro não tem teste próprio.
