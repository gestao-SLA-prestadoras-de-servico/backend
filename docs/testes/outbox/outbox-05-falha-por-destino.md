# Testes — [outbox - 05] Isolar a falha de publicação por destino

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (Postgres e RabbitMQ com o `topologia.json`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório. A verificação no dev usou o ambiente de `make ci-run-dev`, com o app reiniciado.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 488 testes, 0 falhas | ✅ |
| 2 | `PublicadorOutboxTest` com a correção | `-Dtest=PublicadorOutboxTest` (lote de 2) | 5 casos passam | 5 passaram | ✅ |
| 3 | Devolução não bloqueia outros destinos | `shouldKeepReturnedEventPendingWithoutBlockingOtherDestinations`: `inexistente`, `inexistente`, `interna` | 1º pendente, `tentativas` 1 e "Sem fila de destino"; 2º pendente, `tentativas` 0; `interna` publicado e na fila | passou | ✅ |
| 4 | Sem laço com lotes cheios de pulados | `shouldNotLoopWhenFullBatchesHoldOnlySkippedEvents`: 5 `inexistente` e 1 `interna`, lote de 2 | o ciclo termina; `interna` publicado; só 1 tentativa entre os pendentes | passou | ✅ |
| 5 | Broker fora para o ciclo | `shouldKeepEventsPendingWhileBrokerIsDownAndPublishThemWhenItIsBack`, agora com um 2º evento de outro destino | com o broker parado, o 2º evento fica com `tentativas` 0; depois os dois saem | passou | ✅ |
| 6 | Ordem de criação e métricas (casos da outbox-03) | `shouldPublishInCreationOrderAndMarkOnlyAfterConfirm` e `shouldExposePendingCountAndOldestPendingAge`, agora passando por mais de um lote | sem regressão | passaram | ✅ |
| 7 | Quebra: sem keyset (relê do início) | cópia, `-Dtest=PublicadorOutboxTest` | os testes 3 e 4 falham | ambos estouraram o `@Timeout` de 60 s (laço infinito) | ✅ |
| 8 | Quebra: devolução volta a parar o ciclo | idem | os testes 3 e 4 falham | ambos falharam | ✅ |
| 9 | Quebra: não segura o mesmo destino | idem | o teste 3 falha | os testes 3 e 4 falharam | ✅ |
| 10 | Quebra: broker fora não para o ciclo | idem | o teste 5 falha | falhou | ✅ |
| 11 | Dev: passo 7C.4 | evento `inexistente` e, 1 ms depois, `interna`, inseridos pelo psql; espera de 12 s | o 1º pendente com "Sem fila de destino"; o `interna` publicado | 1º com `tentativas` 3 e `Sem fila de destino: NO_ROUTE`; `interna` publicado com `tentativas` 0; log "o destino notificacoes/inexistente espera". O evento `inexistente` foi apagado depois | ✅ |

## Observações

1. **Achado:** a primeira tentativa da quebra 7 travou a execução, porque só o teste 4 tinha timeout. O teste 3 também entra em laço sem keyset, já que o lote de 2 vem cheio dos mesmos pulados. O `@Timeout(threadMode = SEPARATE_THREAD)` passou para a classe toda, e a quebra passou a falhar em vez de travar.
2. **`OffsetDateTime.MIN` não serve como início do keyset:** está fora da faixa do `timestamptz`. O início é a época Unix.
3. **No dev ficou uma mensagem a mais em `notificacoes.interna`**, a do passo 11, além de uma que já estava lá. As filas não foram esvaziadas.
4. **Não verificado:** o nack de verdade (fila com `x-overflow=reject-publish` cheia). O caminho dele é o mesmo da devolução (`DESTINO_FALHOU`), mas nenhum teste o provoca.
5. **Não verificado:** a reação do time do ms-notificacoes à mudança da seção 2.3 de `CONVENÇÕES_RABBITMQ.md`.
