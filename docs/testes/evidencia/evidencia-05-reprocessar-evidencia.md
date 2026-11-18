# Testes — [evidência - 05] Reprocessar evidência com falha

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (PostgreSQL, MinIO e RabbitMQ). As quebras propositais foram feitas no próprio repositório, uma de cada vez, e desfeitas em seguida (`diff -r` contra uma cópia de `src/main`: "diff limpo").

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Unitários | `make unit-test` | tudo passa | 165 testes, 0 falhas | ✅ |
| 2 | Suítes afetadas, JVM em outro fuso | Mesmo comando do item 2 de `evidencia-04-listar-servir-download.md` | tudo passa | 86 testes, 0 falhas, 0 erros (6 do `ReprocessamentoEvidenciaTest`, 11 do `ProcessadorEvidenciasTest`, 16+1 de recepção, 3 do OpenAPI, 8 de auditoria...) | ✅ |

### Os testes

| Teste | O que prova |
| --- | --- |
| `shouldMoveFailedEvidenceBackToReceivedWithNewEventAndAudit` | Evidência enviada pelo endpoint (evento original na outbox) e levada a `FALHA`: 202 `{id, estado: RECEBIDA}`; no banco `RECEBIDA`, `version` + 1 e `updated_at` do Clock; um segundo evento `PROCESSAR_EVIDENCIA` (exchange `evidencias`, routing key `processamento`, não publicado) com `evento_id` diferente do original e o mesmo `chaveOriginal`; auditoria `REPROCESSAMENTO`/`EVIDENCIA` com chamado, ator e perfil do gestor e dados exatamente `{"de":"FALHA","para":"RECEBIDA"}`. |
| `shouldRejectEveryStateOtherThanFailureAndRecordNothing` | `RECEBIDA`, `PROCESSANDO`, `DISPONIVEL` e `EXPURGADA`: 422 `REPROCESSAMENTO_NAO_PERMITIDO`; a linha inteira da evidência igual à de antes, sem auditoria e sem evento. |
| `shouldAnswer403ToTechnicianAndClientAnd401WithoutToken` | Técnico e cliente: 403; sem token: 401; nada muda. |
| `shouldAnswer404ForUnknownEvidence` | Id inexistente: 404 `RECURSO_NAO_ENCONTRADO`. |
| `shouldRejectASecondRequestInARow` | 202 e depois 422; um evento e uma auditoria. |
| `shouldLetOnlyOneOfTwoSimultaneousRequestsThrough` | Dois pedidos simultâneos (duas threads, largada por latch): um 202 e um 422; um evento, uma auditoria, `RECEBIDA`. |
| `ProcessadorEvidenciasTest.shouldReprocessFailedEvidenceToAvailableAndDiscardTheOldDlqMessage` | Ponta a ponta com RabbitMQ e MinIO reais: o storage falha, a evidência vai a `FALHA` e a mensagem à DLQ; com o storage de volta, `POST` do gestor, publicador e listener levam a `DISPONIVEL` (3 objetos no storage, DLQ vazia); a mensagem antiga, reinjetada à mão, é descartada: estado `DISPONIVEL` e nenhuma gravação nova. |

## Quebras propositais (cada teste pega o erro)

| # | Quebra | Testes que falharam | Falha observada |
| --- | --- | --- | --- |
| R1 | `UPDATE` aceita também `RECEBIDA` | `shouldRejectEveryStateOtherThanFailureAndRecordNothing`, `shouldRejectASecondRequestInARow`, `shouldLetOnlyOneOfTwoSimultaneousRequestsThrough` | esperado 422, veio 202 |
| R2 | Sem a auditoria | `shouldMoveFailedEvidence...`, `shouldRejectASecondRequestInARow`, `shouldLetOnlyOneOfTwo...` | nenhuma linha de auditoria |
| R3 | Sem o evento na outbox | `shouldMoveFailedEvidence...`, `shouldRejectASecondRequestInARow`, `shouldLetOnlyOneOfTwo...` | nenhum evento; o ponta a ponta estoura o timeout esperando `DISPONIVEL` (`ConditionTimeoutException`) |
| R4b | `@PreAuthorize` e regra de rota abertos a qualquer papel | `shouldAnswer403ToTechnicianAndClientAnd401WithoutToken` | esperado 403, veio 202 (quebrar só uma das duas camadas não derruba o teste: a outra segura) |
| R5 | Id inexistente dá 422 em vez de 404 | `shouldAnswer404ForUnknownEvidence` | esperado 404, veio 422 |
| R6 | `eventoId` fixo no evento | `shouldMoveFailedEvidence...`, `shouldRejectASecondRequestInARow` | violação da unicidade de `evento_id` (500) / evento repetido |
| R7 | `UPDATE` sem a condição de estado | `shouldRejectEveryState...`, `shouldRejectASecondRequestInARow`, `shouldLetOnlyOneOfTwo...` | esperado 422, veio 202 |
| R7c | `UPDATE` sem condição, com conferência do estado só no Java (leitura e depois escrita) | `shouldLetOnlyOneOfTwoSimultaneousRequestsThrough` | os dois pedidos passam (202 e 202); só este teste pega, os sequenciais não |
| R8 | `updated_at` com uma data fixa em vez do Clock | `shouldMoveFailedEvidence...` | `updated_at` diferente de `AGORA` |
| R9 | `UPDATE` sem incrementar `version` | `shouldMoveFailedEvidence...` | `version` igual à de antes |
| R10 | Auditoria com o nome do arquivo nos dados | `shouldMoveFailedEvidence...` | dados diferentes de `{"de","para"}` |
| E1 | (ponta a ponta) sem o evento na outbox | `shouldReprocessFailedEvidenceToAvailableAndDiscardTheOldDlqMessage` | `ConditionTimeoutException`: a evidência não chega a `DISPONIVEL` |
| E2 | (ponta a ponta) `reivindicar` aceita também `DISPONIVEL` | `shouldReprocessFailedEvidenceToAvailableAndDiscardTheOldDlqMessage` | `AssertionFailedError` (a mensagem antiga é processada de novo em vez de descartada; a asserção exata não foi anotada) |

## Observações

1. **R7c:** o teste de concorrência depende do entrelaçamento das duas threads (as duas precisam ler `FALHA` antes de qualquer `UPDATE`). A quebra foi pega na execução feita, mas o teste, por natureza, não garante ver o erro em toda execução; com o código de produção correto ele é determinístico (um 202 e um 422).
2. **Mensagem antiga com a evidência ainda em `FALHA`:** é descartada (o `GeradorDerivados` só reivindica `RECEBIDA` e `PROCESSANDO`); está coberta pelo `shouldDiscardAvailableAndExpungedEvidenceWithoutProcessing` da evidencia-03 (que inclui `FALHA`).
3. **Não verificado:**
   - mover a mensagem antiga da DLQ pelo console do RabbitMQ (7J.7); o resto da seção 7J foi verificado no dev(verificado no dev em 2026-11-16: ver "Verificação no ambiente dev" na evidencia-04);
   - a suíte completa com `TZ=UTC ./mvnw clean test`;
   - reprocessamento de evidência cujo original foi removido do storage (o reprocessamento vai a `FALHA` de novo, sem teste específico).
