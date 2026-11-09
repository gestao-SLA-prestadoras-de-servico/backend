# [outbox - 03] Publicar os eventos pendentes

> Requisitos: RF-50, RF-64, RN-114, RN-115, RN-116 · Depende de: [outbox - 01], [outbox - 02]

## Contexto

Um publicador lê os eventos pendentes da outbox e os entrega ao RabbitMQ com confirmação do broker. Só depois da confirmação o evento é marcado como publicado. A entrega é "ao menos uma vez": todos os consumidores são idempotentes. É a primeira rotina agendada do projeto.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Com o RabbitMQ parado, os eventos ficam pendentes; quando ele volta, são publicados sem perda | Falha de conexão (`AmqpException`) ou confirm que não chega em `espera-confirmacao` deixa o evento pendente, com `tentativas` e `ultimo_erro`. O próximo ciclo tenta de novo; a conexão do `CachingConnectionFactory` é recriada sozinha. |
| Mensagem sem fila de destino conta como falha e o evento continua pendente | `mandatory=true` + `publisher-returns`: o broker devolve a mensagem antes do ack, e o `CorrelationData.getReturned()` preenchido conta como falha. |
| Os eventos são publicados em ordem de criação; na primeira falha, o lote para | `order by criado_em, id`, um evento por vez, esperando o confirm; na primeira falha o ciclo termina. |
| Existe métrica de eventos pendentes e da idade do mais antigo | `MetricasOutbox` (Micrometer): `outbox_eventos_pendentes` e `outbox_pendente_mais_antigo_idade_seconds`. |
| Testes de integração criados e passando | `PublicadorOutboxTest` (4 casos), com Postgres e RabbitMQ reais (Testcontainers). |

## Passos técnicos realizados

- [x] **`PublicadorOutbox` com `@Scheduled(fixedDelay)` configurável**: `app.outbox.publicador.intervalo` (5 s), também como atraso inicial. `@EnableScheduling` em `shared.internal.AgendamentoConfig`.
- [x] **`RabbitTemplate` com publisher confirms e `mandatory=true` + `ReturnsCallback`**: `spring.rabbitmq.publisher-confirm-type=correlated`, `publisher-returns=true`, `template.mandatory=true`. O `ReturnsCallback` (via `RabbitTemplateCustomizer`) registra a devolução no log; quem decide é o publicador, pelo `CorrelationData`.
- [x] **`messageId` = `eventoId`, `contentType` JSON e entrega persistente**: também `type` = tipo do evento e `contentEncoding` UTF-8.
- [x] **Marcar `publicado_em` só após o confirm**: o `update` roda depois do ack sem devolução; `publicado_em` vem do `Clock`.
- [x] **Métricas no Micrometer**: `MeterBinder` com dois gauges que consultam o banco a cada coleta.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Métricas | Gauges que consultam o banco a cada coleta | Ficam corretas mesmo se o publicador travar; um valor gravado pelo publicador congelaria e esconderia o problema. A consulta usa o índice parcial dos pendentes. |
| Ciclo | Esvazia em lotes de 100 enquanto vierem lotes cheios | Depois de o broker voltar, o acúmulo sai no mesmo ciclo, não a 100 por intervalo. |
| Transação | Nenhuma no ciclo; cada evento é marcado logo após o confirm | Não segura conexão do banco enquanto espera o broker. Uma queda entre confirm e marcação republica o evento (RN-115). |
| Configuração | Intervalo 5 s, lote 100, espera do confirm 5 s em `application.properties`, sem variável de ambiente | Não muda entre ambientes. |
| Testes | Intervalo de 1 h no perfil de teste; o teste chama o publicador direto (`PublicadorOutboxTestHelper`) | Rodando sozinho, o publicador incrementaria `tentativas` no meio de outros testes, sem broker. |
| Broker parado no teste | `rabbitmqctl stop_app`/`start_app` | Derruba o AMQP sem trocar a porta mapeada, e as filas duráveis voltam. |
| Container do RabbitMQ nos testes | Extraído para `BrokerTeste`, usado também pelo `TopologiaRabbitTest` | Um container por JVM, em vez de um por classe. |
| Desempate na ordem | `order by criado_em, id` | Eventos da mesma transação têm o mesmo `criado_em` (ex.: dois canais); o `id` deixa a ordem estável entre ciclos. |
| Desligamento | `spring.task.scheduling.shutdown.await-termination=true` (60 s) | O ciclo em andamento termina antes de a aplicação parar. |

## Atenção ao atualizar um ambiente existente

- Não há migração nem variável nova. As propriedades novas estão no `application.properties`.
- **Um evento que nunca encontra fila trava a outbox**: o ciclo para nele, e todos os eventos criados depois esperam (ordem de criação, como pede a task). A métrica `outbox_pendente_mais_antigo_idade_seconds` crescendo mostra isso. Hoje não há código que grave routing key fora da topologia; se acontecer, corrija a topologia ou apague o evento.
- **`/actuator/prometheus` exige token** (`SegurancaConfig` só libera `/actuator/health/**`). Um coletor Prometheus sem token não lê as métricas; ver docs de testes.

## Como verificar

Ver `docs/testes/outbox/outbox-03-publicar-eventos-pendentes.md` e a seção 7C de `docs/plano-testes-manuais.md`.
