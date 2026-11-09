# Testes — [outbox - 01] Criar a tabela de saída e o registro transacional

> Executados em 09/11/2026, na branch `feat/outbox` (criada a partir de `fix/autenticacao-06-revisao`; alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 137 testes, 0 falhas (`OutboxTest` 5 e os anteriores) | ✅ |
| 2 | Fora de transação | `OutboxTest.shouldFailOutsideTransaction` | `IllegalTransactionStateException` | passou | ✅ |
| 3 | Gravação na transação de quem chama | `shouldStorePendingEventWithinCallerTransaction` (`RelogioMutavel` em 2026-11-09T12:00Z) | `criado_em` = hora do Clock; `publicado_em` e `ultimo_erro` nulos; `tentativas` 0; `eventoId` no payload | passou | ✅ |
| 4 | Rollback da operação | `shouldLeaveNoEventWhenBusinessOperationRollsBack` | nenhum registro | passou | ✅ |
| 5 | `eventoId` repetido | `shouldIgnoreExistingEventIdWithoutErrorOrDuplicate` (segunda gravação com outro título) | sem erro, 1 linha, payload original | passou | ✅ |
| 6 | Dois canais | `shouldStoreOneRecordPerChannelWithDistinctDeterministicIds` (registrado duas vezes) | 2 linhas, uma por routing key, ids derivados e distintos, cada payload com o próprio id; a repetição não cria linhas | passou | ✅ |
| 7 | Quebra: `REQUIRED` no lugar de `MANDATORY` | cópia do projeto, `-Dtest=OutboxTest` | o teste 2 falha | `shouldFailOutsideTransaction` falhou | ✅ |
| 8 | Quebra: `REQUIRES_NEW` (transação própria) | idem | o teste 4 falha | `shouldFailOutsideTransaction` e `shouldLeaveNoEventWhenBusinessOperationRollsBack` falharam | ✅ |
| 9 | Quebra: sem `ON CONFLICT` | idem | o teste 5 falha | `DuplicateKey` em `outbox_eventos_evento_id_key` (testes 5 e 6) | ✅ |
| 10 | Quebra: mesmo `eventoId` nos dois canais | idem | o teste 6 falha | `shouldStoreOneRecordPerChannelWithDistinctDeterministicIds` falhou | ✅ |

## Observações

1. **Não verificado no ambiente de dev:** a `V6` foi aplicada só no Postgres do Testcontainers. A subida no banco de dev não foi conferida.
2. **Uso do índice parcial não verificado:** não houve `EXPLAIN`. O índice existe para a consulta do publicador, que ainda não existe.
3. **Nenhum módulo chama o `OutboxService` ainda**; a integração com as operações de negócio vem nas tasks de chamado e evidência.
