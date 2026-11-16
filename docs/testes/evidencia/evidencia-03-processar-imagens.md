# Testes — [evidência - 03] Processar imagens em segundo plano

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`, MinIO e `rabbitmq:4.1-management-alpine` com a topologia de `infra/rabbitmq`). As quebras propositais foram feitas no próprio repositório, uma de cada vez, e desfeitas em seguida (conferido com `diff -r` contra uma cópia de `src/` tirada antes: "diff limpo" em todas).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Unitários | `make unit-test` | tudo passa | 165 testes, 0 falhas (160 anteriores + 5 do `RedimensionadorTest`) | ✅ |
| 2 | Suítes afetadas, JVM em outro fuso | `TZ=UTC ./mvnw -Dtest='ProcessadorEvidenciasTest,RecepcaoEvidencia*Test,PublicadorOutboxTest,TopologiaRabbitTest,OutboxTest,BackendApplicationTests,OpenApiContratoTest,ArmazenamentoEvidenciasTest' test` | tudo passa, sem erro de conexão ao RabbitMQ nos contextos antigos | 59 testes, 0 falhas, 0 erros (10 do `ProcessadorEvidenciasTest`, 16+1 de recepção, 4 do publicador, 9 da topologia, 5 da outbox, 6 do contexto, 3 do OpenAPI, 5 do storage) | ✅ |
| 3 | Redimensionamento | `RedimensionadorTest` (5): lado maior 1280/320 mantendo a proporção (paisagem e retrato), sem ampliar imagem menor, mínimo de 1 px, transparência sobre branco com cor preservada, JPEG sem `Exif` | passa | 5 testes, 0 falhas | ✅ |
| 4 | Fluxo feliz publicado de verdade | `shouldProcessUploadedEvidenceToAvailableWithThumbnailAndPreview`: envio 2000x1500 pelo endpoint, ciclo do publicador, espera `DISPONIVEL` | `DISPONIVEL`, miniatura 320x240 e visualização 1280x960 lidas do MinIO, original com o mesmo SHA-256 (de antes e do banco), 3 objetos, fila e DLQ vazias | passou | ✅ |
| 5 | Mensagem duplicada | `shouldGenerateDerivativesOnlyOnceWhenTheSameMessageIsDeliveredTwice`: mesma mensagem duas vezes, a segunda depois de `DISPONIVEL` | 2 `putObject` no total (spy no `S3Client`), objetos e `lastModified` iguais, DLQ vazia | passou | ✅ |
| 6 | DISPONIVEL e EXPURGADA descartadas | `shouldDiscardAvailableAndExpungedEvidenceWithoutProcessing` (inclui `FALHA` e id inexistente) | estados iguais, 0 `getObject`, 0 `putObject`, só o original no storage, fila e DLQ vazias | passou | ✅ |
| 7 | Falha temporária esgotada | `shouldTryThreeTimesThenMarkFailureAndSendTheMessageToTheDlq`: spy faz `getObject` lançar `SdkClientException` | 3 `getObject`, `FALHA`, mensagem na `evidencias.processamento.dlq`, fila principal vazia | passou | ✅ |
| 8 | Recupera na 2ª tentativa | `shouldBecomeAvailableWhenTheSecondAttemptSucceeds` | 2 `getObject`, `DISPONIVEL`, DLQ vazia | passou | ✅ |
| 9 | Imagem que não decodifica | `shouldFailImmediatelyWhenTheImageDoesNotDecode` (texto gravado como original) | 1 `getObject`, `FALHA`, mensagem na DLQ, fila vazia | passou | ✅ |
| 10 | FALHA não toca no chamado | `shouldNotChangeTheChamadoNorTheAttendanceWhenTheEvidenceFails` | linhas inteiras de `chamado` e `atendimento` (inclusive `version` e `updated_at`) e o histórico iguais | passou | ✅ |
| 11 | Ack só depois de gravar | `shouldAckOnlyAfterTheResultIsPersisted`: trigger de teste faz o `UPDATE ... DISPONIVEL` dormir 3 s; com a evidência em `PROCESSANDO`, `rabbitmqctl list_queues` (consulta viva) | 1 mensagem sem ack na fila enquanto o resultado não foi gravado; depois `DISPONIVEL` e 0/0 | passou | ✅ |
| 12 | Métrica por estado | `shouldExposeEvidenceCountPerState` (1, 2, 3, 4 e 5 evidências nos cinco estados) | gauge `evidencias{estado}` com os valores certos no `MeterRegistry` | passou | ✅ |
| 13 | Pico de 50 envios | `shouldKeepApiLatencyUnderProvisionalLimitDuringA50UploadBurst`: JPEG 3000x2000, 50 envios simultâneos por HTTP real, publicador a cada 500 ms, `GET /api/chamados/{id}` em sequência | as 50 chegam a `DISPONIVEL`; latência dentro do limite provisório | ver "Números do pico" | ✅ |

## Números do pico (limite NÃO combinado)

O spec não traz o "limite combinado" e o usuário não informou. As asserções do teste são **provisórias e frouxas**: p95 durante o processamento < 5 x p95 da base + 200 ms, e p95 durante os envios < 5 x p95 da base + 500 ms. Máquina de desenvolvimento, tudo (app, Postgres, MinIO, RabbitMQ) no mesmo host.

| Execução | Base (100 GET, p50 / p95) | Durante os envios (p95) | Durante o processamento (p95) | Máximo no pico |
| --- | --- | --- | --- | --- |
| 1 | 9 / 11 ms | 262 ms (n=34) | 10 ms (n=1007) | 297 ms |
| 2 | 9 / 12 ms | 253 ms (n=37) | 9 ms (n=979) | 278 ms |
| 3 | 9 / 11 ms | 265 ms (n=38) | 11 ms (n=978) | 287 ms |
| 4 (suíte afetada) | 11 / 13 ms | 198 ms (n=42) | 10 ms (n=986) | 380 ms |

Leitura: o processamento em segundo plano não mexeu na latência (p95 de 9 a 11 ms, igual à base). A degradação (p95 de 200 a 265 ms, ~20x a base) acontece **enquanto chegam os 50 uploads**, e vem do próprio envio (decodificar e regravar 50 fotos de 6 MP na recepção, mais o parse do multipart), não do consumidor. Se o limite combinado for mais apertado que o provisório, essa fase é a que precisa de atenção (por exemplo, limitar a concorrência da sanitização). Os 50 envios e as 50 evidências `DISPONIVEL` levam cerca de 10 s depois dos envios. O teste inteiro dura ~40 s. Misturar as duas fases numa amostra só daria p95 de 13 a 15 ms (diluição pelo número de amostras), por isso são medidas e afirmadas à parte.

## Quebras propositais (cada teste pega o erro)

| # | Quebra | Testes que falharam | Falha observada |
| --- | --- | --- | --- |
| A | `Redimensionador` sem `Math.min(1.0, ...)` (amplia) | `shouldNotEnlargeAnImageSmallerThanTheTarget` | tamanho maior que o original |
| B | `Redimensionador` sem o `fillRect` branco | `shouldFlattenTransparencyOnWhiteAndKeepColors` | `[transparente vira branco]` |
| C | `reivindicar` aceita também `DISPONIVEL` | `shouldGenerateDerivativesOnlyOnceWhenTheSameMessageIsDeliveredTwice`, `shouldDiscardAvailableAndExpungedEvidenceWithoutProcessing` | 4 gravações em vez de 2; 2 gravações em vez de 0 |
| D | `reivindicar` aceita também `EXPURGADA` | `shouldDiscardAvailableAndExpungedEvidenceWithoutProcessing` | estado esperado `EXPURGADA`, veio `DISPONIVEL` |
| E | `retry-exceptions` sem `ServicoIndisponivelException` | `shouldTryThreeTimesThenMarkFailureAndSendTheMessageToTheDlq`, `shouldBecomeAvailableWhenTheSecondAttemptSucceeds` | 1 tentativa em vez de 3; `FALHA` em vez de `DISPONIVEL` |
| F | `max-attempts=5` | `shouldTryThreeTimes...` | 5 tentativas em vez de 3 |
| G | `ImagemIlegivelException` entra nos `retry-exceptions` | `shouldFailImmediatelyWhenTheImageDoesNotDecode` | 3 leituras em vez de 1 |
| H | O processador não marca `FALHA` | `shouldTryThreeTimes...`, `shouldFailImmediately...`, `shouldNotChange...` | estado `PROCESSANDO` em vez de `FALHA` (timeout de 30 s) |
| I | O processador engole a exceção (sem rejeitar) | `shouldTryThreeTimes...`, `shouldFailImmediately...` | mensagem nula na DLQ |
| J | `acknowledge-mode=none` (ack na entrega) | `shouldAckOnlyAfterTheResultIsPersisted` (e os dois da DLQ) | 0 mensagens sem ack, esperado 1 |
| K | O processador atualiza o `chamado` ao falhar | `shouldNotChangeTheChamadoNorTheAttendanceWhenTheEvidenceFails` | `updated_at` do chamado diferente |
| L | Métrica conta sempre `DISPONIVEL` | `shouldExposeEvidenceCountPerState` | `RECEBIDA` esperado 1, veio 3 |
| M | Miniatura gerada com o lado da visualização | `shouldProcessUploadedEvidenceToAvailableWithThumbnailAndPreview` | dimensões da miniatura erradas |
| N | Listener consome toda a CPU por 1,5 s por mensagem | **nenhum** (o teste do pico passou, 88 s) | ver observações |

## Observações

1. **O teste do pico não pegou a quebra N.** Um listener que ocupa a CPU (fork-join comum, 64 tarefas, 1,5 s por mensagem) não elevou o p95 acima do limite provisório: o escalonador do SO divide a CPU, e as consultas de ~10 ms cabem nas fatias. Ou seja, a asserção é só uma salvaguarda grosseira, não prova a ausência de degradação. Ela existe como medição e para barrar regressões grandes; o limite real depende do valor combinado.
2. **Ack:** a prova é observável (mensagem sem ack durante o `UPDATE` lento, vista por `rabbitmqctl list_queues`, que consulta o processo da fila na hora; a API de gerenciamento só atualiza a cada 5 s). `default-requeue-rejected=false` não tem quebra própria: o `AmqpRejectAndDontRequeueException` explícito já impede o requeue; a propriedade é só rede de segurança e **não foi provada isoladamente**.
3. **Contagem de chamadas no spy:** o `S3Client` espiado registra a sobrecarga com `Consumer` e a delegação interna para a de `Request`; o teste conta só a primeira (a que o `ArmazenamentoEvidencias` chama), senão tudo dobra.
4. **Isolamento:** o `ProcessadorEvidenciasTest` usa `@DirtiesContext(AFTER_CLASS)`: com o contexto vivo, o consumidor roubaria as mensagens que o `TopologiaRabbitTest` deixa em `evidencias.processamento` (broker único por JVM). Cada teste limpa as filas, a outbox e a tabela `evidencia`.
5. **Mensagem duplicada simultânea** (duas entregas em `PROCESSANDO` ao mesmo tempo) não é testada: é a limitação documentada (sobrescrita idêntica).
6. **Não verificado:**
   - o fluxo no ambiente dev (`make run-dev`, seção 7H do plano de testes manuais, incluindo ver a DLQ no console do RabbitMQ);
   - a suíte completa com `TZ=UTC ./mvnw clean test` (rodei só as suítes afetadas listadas no item 2);
   - banco fora do ar durante o processamento (o caminho "não consegui nem marcar `FALHA`" não tem teste);
   - imagem real de 40 MP no consumidor (consumo de heap com concorrência 2);
   - fotos reais de câmera (CMYK, progressivo) na geração dos derivados;
   - queda da aplicação no meio do processamento (a recuperação por `PROCESSANDO` está só no desenho, sem teste);
   - o limite de latência real (não existe um combinado).
