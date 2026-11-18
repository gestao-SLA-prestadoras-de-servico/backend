# [evidência - 03] Processar imagens em segundo plano

> Requisitos: RF-61, RN-78, RN-82, RN-84, RN-86 a RN-90, RN-115 · Depende de: [evidência - 02], [outbox - 02], [outbox - 03]

## Contexto

A recepção (evidência - 02) responde 202 com a evidência `RECEBIDA` e deixa um evento `PROCESSAR_EVIDENCIA` na outbox. Esta task é o consumidor: um `@RabbitListener` interno do app-principal lê a fila `evidencias.processamento`, gera a miniatura e a visualização a partir do original (que nunca é alterado) e leva a evidência a `DISPONIVEL`. Falha temporária tem retry com backoff; falha definitiva deixa a evidência em `FALHA` e a mensagem na DLQ, sem tocar no atendimento nem no chamado.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| RECEBIDA → PROCESSANDO → DISPONIVEL, com miniatura (~320 px) e visualização (~1280 px) no MinIO | `GeradorDerivados.gerar`: reivindica (PROCESSANDO), lê o original, `Redimensionador.jpeg` (lado maior 320 e 1280) e grava em `ChaveObjeto.miniatura()`/`visualizacao()`; `concluir` põe `DISPONIVEL` e as duas chaves. |
| A mesma mensagem entregue duas vezes gera os derivados uma vez | A segunda entrega encontra `DISPONIVEL`, a reivindicação atualiza 0 linhas e a mensagem é descartada com ack (RN-86, RN-115). |
| DISPONIVEL ou EXPURGADA é descartada sem processar | Mesmo mecanismo: só `RECEBIDA` e `PROCESSANDO` são reivindicáveis (`FALHA` e id inexistente também são descartados). |
| Falha temporária tenta 3 vezes com backoff; esgotadas, FALHA e DLQ | `@Retry(name = "evidencias")` (Resilience4j; 3 tentativas, 1 s e 2 s). O `ProcessadorEvidencias` marca `FALHA` e lança `AmqpRejectAndDontRequeueException`; a DLX da topologia leva a mensagem à `evidencias.processamento.dlq`. |
| Imagem que não decodifica vai direto para FALHA | `ImagemIlegivelException` não está nos `retry-exceptions`: uma tentativa, `FALHA`, DLQ. |
| FALHA não altera o atendimento nem o chamado | O processador não usa nada do módulo chamado; o teste compara as linhas inteiras antes e depois. |
| O ack só acontece depois de gravar o resultado | Ack automático do container: sai quando o método do listener retorna, que só retorna depois do commit do `UPDATE ... DISPONIVEL` (sem `@Transactional` ao redor). |
| Um pico de 50 envios não degrada a latência da API além do limite combinado | **O limite não foi combinado.** Teste de carga com asserção frouxa e provisória; números em `docs/testes/evidencia/evidencia-03-processar-imagens.md`. Concorrência limitada por propriedade (RN-84). |

## Passos técnicos realizados

- [x] `ProcessadorEvidencias` (`@RabbitListener(queues = "evidencias.processamento")`): extrai o `evidenciaId`, chama o gerador, marca `FALHA` e rejeita sem requeue em qualquer exceção.
- [x] `GeradorDerivados` com `@Retry`, bean separado do listener (o retry é por proxy).
- [x] `Redimensionador` (Java2D): passos de 1/2 (bilinear) e passo final bicúbico, JPEG 0,85 sem metadados (reusa `SanitizadorImagem.escrever`, agora package-private), fundo branco sob transparência, sem ampliar.
- [x] `EvidenciaRepository`: três `UPDATE`s condicionais (`reivindicar`, `concluir`, `falhar`), cada um na própria transação curta.
- [x] `MetricasEvidencias`: gauge `evidencias{estado}`, um por `EstadoEvidencia`, contado no banco.
- [x] Propriedades: `spring.rabbitmq.listener.simple.concurrency/max-concurrency/prefetch/default-requeue-rejected` e `resilience4j.retry.instances.evidencias.*`.
- [x] Nenhuma fila, exchange ou binding declarado no app (`TopologiaRabbitArquiteturaTest`).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Retry | Resilience4j, não o retry do listener do Spring AMQP | Decisão do usuário (mesma biblioteca da autenticação-03, que substituiu o `@Retryable`). `max-attempts=3` conta a primeira; `retry-exceptions` = `ServicoIndisponivelException`, `TransientDataAccessException` e `DataAccessResourceFailureException`. |
| Recuperador | Marca `FALHA` numa transação própria e lança `AmqpRejectAndDontRequeueException`; o broker encaminha pela DLX | A topologia já roteia para a DLQ: não se republica à mão. Vale também para exceção inesperada. `default-requeue-rejected=false` é a rede de segurança contra loop de requeue. |
| Se o banco também está fora | A evidência segue `PROCESSANDO` e a mensagem vai à DLQ do mesmo jeito | `PROCESSANDO` é reivindicável: reprocessar a partir da DLQ funciona. |
| Idempotência | `UPDATE` condicional (`RECEBIDA`/`PROCESSANDO` vira `PROCESSANDO`); 0 linhas = descarta com ack | `PROCESSANDO` entra para recuperar processamento interrompido por queda. Como JPQL não passa pelo `@Version` nem pelo `@UpdateTimestamp`, os três `UPDATE`s incrementam `version` e gravam `updated_at` (Clock) explicitamente. |
| Limitação | Duas entregas **simultâneas** da mesma mensagem, ambas em `PROCESSANDO`, gerariam os derivados duas vezes | Os derivados são determinísticos e sobrescrevem a mesma chave (resultado idêntico); na prática o publicador republica em ciclos de 5 s. Marcado com `ponytail:` no `GeradorDerivados`. |
| Derivados | Java2D puro, sem Thumbnailator; JPEG 0,85 sem metadados; lado maior 320 e 1280; sem ampliar; fundo branco sob alfa | Decisão do usuário. O original é lido inteiro em memória (até 10 MB) antes de decodificar: falha de rede na leitura é transitória, falha de decodificação não. |
| Concorrência | Propriedades do Boot (`concurrency=1`, `max-concurrency=2`, `prefetch=1`), sem fábrica própria | É o único `@RabbitListener` do app. Se surgir outro, ele ganha fábrica própria. |
| Ack | Automático do container, sem `@Transactional` no listener | O ack sai quando o método retorna, depois do commit do `UPDATE`. Um `@Transactional` no listener comitaria depois do ack. |
| Testes | `spring.rabbitmq.listener.simple.auto-startup=false` em `application-test.properties`; só o `ProcessadorEvidenciasTest` liga (`true`) e aponta para o `BrokerTeste` | Os demais testes não têm RabbitMQ; um listener ligado faria todo contexto tentar conectar. O teste usa `@DirtiesContext(AFTER_CLASS)` para o consumidor não roubar mensagens do `TopologiaRabbitTest` (broker único por JVM). `wait-duration` do retry cai para 20 ms nos testes. |
| Métrica | Um `select count(*) ... where estado = ?` por gauge, em vez de um `group by` | Cinco consultas leves por coleta em vez de uma varredura agrupada compartilhada que exigiria cache; não há índice em `estado` (tabela pequena por ora). |
| Limite de latência | **Provisório e não combinado:** p95 durante o processamento < 5 x p95 da base + 200 ms; p95 durante os envios < 5 x base + 500 ms | O spec não traz o limite e o usuário não informou. É só uma salvaguarda contra regressão grosseira (ver observações dos testes). |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável de ambiente nova.
- Mensagens que estavam acumuladas em `evidencias.processamento` (a task 02 não tinha consumidor) são processadas na primeira subida.
- Evidência em `FALHA` não é reprocessada por republicação; o reprocessamento é o `POST /api/evidencias/{id}/reprocessamento` (evidência - 05).
- A fila `evidencias.processamento.dlq` não expira: acompanhar pelo console do RabbitMQ.

## Como verificar

Ver `docs/testes/evidencia/evidencia-03-processar-imagens.md` e a seção 7H do `docs/plano-testes-manuais.md`.
