# Testes — [outbox - 03] Publicar os eventos pendentes

> Executados em 09/11/2026, na branch `feat/outbox` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17` e `rabbitmq:4.1-management-alpine` com o `topologia.json`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 151 testes, 0 falhas (`PublicadorOutboxTest` 4 e os 147 anteriores) | ✅ |
| 2 | Ordem de criação, propriedades da mensagem, marcação após o confirm | `PublicadorOutboxTest.shouldPublishInCreationOrderAndMarkOnlyAfterConfirm` (3 eventos gravados fora de ordem) | mensagens na ordem de `criado_em`; `messageId` = `eventoId`, `application/json`, persistente, `type`; `publicado_em` = hora do Clock | passou | ✅ |
| 3 | Sem fila de destino; o lote para | `shouldKeepEventPendingWhenThereIsNoDestinationQueueAndStopTheBatch` (routing key `inexistente` seguida de um evento válido) | o primeiro fica pendente com `tentativas` 1 e "Sem fila de destino"; o seguinte não sai | passou | ✅ |
| 4 | Broker parado e de volta | `shouldKeepEventsPendingWhileBrokerIsDownAndPublishThemWhenItIsBack` (`stop_app`, ciclo, `start_app`, ciclo) | pendente com `tentativas` 1; depois publicado e na fila | passou | ✅ |
| 5 | Métricas | `shouldExposePendingCountAndOldestPendingAge` (2 pendentes, o mais antigo há 3600 s) | 2 e 3600; depois da publicação, 0 e 0 | passou | ✅ |
| 6 | Quebra: lote não para na falha (`continue`) | cópia, `-Dtest=PublicadorOutboxTest` | o teste 3 falha | falhou | ✅ |
| 7 | Quebra: sem `mandatory`/`publisher-returns` | idem | o teste 3 falha | falhou (evento sem fila marcado como publicado) | ✅ |
| 8 | Quebra: marcar `publicado_em` antes do envio | idem | os testes 3 e 4 falham | ambos falharam | ✅ |
| 9 | Quebra: idade pelo `max(criado_em)` | idem | o teste 5 falha | falhou | ✅ |
| 10 | Quebra: `order by criado_em desc` | idem | o teste 2 falha | falhou (e o 3 também) | ✅ |
| 11 | Quebra: sem `order by` | idem | o teste 2 falha | **passou** | ⚠️ ver observação 1 |
| 12 | Plano da consulta do publicador | `EXPLAIN` num `postgres:17` descartável com a tabela e o índice da V6 | usa o índice parcial | `Index Scan using outbox_eventos_pendentes_idx` + `Incremental Sort (Presorted Key: criado_em)` | ✅ |
| 13 | Dev: publicação | stack de dev completa a partir da cópia (`-p flowsla-verificacao`, volumes próprios); evento inserido pelo psql | publicado em até 5 s | publicado; `notificacoes.interna` com 1 mensagem | ✅ |
| 14 | Dev: broker parado e de volta | `docker stop` do RabbitMQ, evento com routing key `email`, `docker start` | pendente enquanto parado; publicado depois | publicado cerca de 10 s após o `start`, com `tentativas` = 1; `notificacoes.email` com 1 mensagem | ✅ |
| 15 | Dev: métricas no `/actuator/prometheus` | `curl` sem token | métricas `outbox_*` | **401**: o endpoint exige token | ⚠️ ver observação 2 |

## Observações

1. **Achado:** sem `ORDER BY`, a consulta continua saindo em ordem de `criado_em` porque o Postgres lê pelo índice parcial `(criado_em)` (item 12). O teste não tem como detectar a remoção, mas o comportamento não é garantido sem o `ORDER BY`, que fica. A ordem invertida é detectada (item 10).
2. **Achado (anterior a esta task):** `/actuator/prometheus` responde 401 sem token, porque a `SegurancaConfig` só libera `/actuator/health/**`. Um coletor Prometheus não lê as métricas. Liberar o endpoint é decisão de segurança, a combinar; não foi alterado.
3. **Não verificado:** os valores das métricas no dev. Nenhum client do realm permite login direto (`directAccessGrantsEnabled` desligado), e o token só sairia pelo navegador. Os valores estão provados no item 5.
4. **Achado (ambiente):** na cópia em `/tmp`, o `rabbitmq-init` falhou com `Permission denied` ao ler `/rabbitmq/topologia.json`. O volume `./rabbitmq:/rabbitmq` não tem `:z`, e o arquivo da cópia tinha rótulo SELinux `user_tmp_t`. No repositório o arquivo já está como `container_file_t` (reetiquetado pelo volume `..:/app:z` do backend de dev). Quem nunca subiu o backend de dev numa máquina com SELinux pode ter o mesmo erro. O compose não foi alterado.
5. **Observação de ambiente:** o ambiente de verificação foi derrubado com `down -v` apenas no projeto `flowsla-verificacao`; os volumes `infra_*` de dev não foram tocados.
6. **Fecha um ponto da outbox-01:** o uso do índice parcial pela consulta do publicador, que lá ficou sem verificação, foi confirmado no item 12.
