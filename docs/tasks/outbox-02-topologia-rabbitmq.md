# [outbox - 02] Definir a topologia do RabbitMQ

> Requisitos: RF-51, RN-48, RN-56, RN-57 · Depende de: [plataforma - 02]

## Contexto

Se uma mensagem é publicada numa exchange sem fila vinculada, o broker confirma e descarta a mensagem. Com o ms-notificacoes ainda não iniciado, a outbox marcaria como publicado um evento perdido. Por isso as filas precisam existir antes de qualquer publicação, com uma única fonte de verdade compartilhada pelos dois serviços.

Antes desta task, o `definitions.json` era gerado pelo `rabbitmq-init` (`generate_definitions.py`, commit `01afaa4`) e ignorado pelo git, porque leva o hash da senha. A topologia era só uma exchange `outbox.eventos` (topic) que nenhuma regra usava.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| O broker sobe com exchanges, filas e DLQs já criadas, sem depender de nenhum serviço | Topologia em `infra/rabbitmq/topologia.json`. O `generate_definitions.py` junta o arquivo ao usuário, e o broker carrega o `definitions.json` no boot (`rabbitmq.conf`). |
| Com o ms-notificacoes nunca iniciado, os eventos publicados ficam na fila | Filas `durable` criadas no boot, independentes de consumidor. `TopologiaRabbitTest` e verificação no dev, com restart do broker. |
| Mensagem na DLQ de notificação expira em 7 dias | `x-message-ttl` = 604800000 nas duas DLQs de notificação (RN-57). |
| Os serviços não declaram filas com argumentos próprios | Backend: `TopologiaRabbitArquiteturaTest` (ArchUnit) reprova `Declarable` e os builders do Spring AMQP. ms-notificacoes: combinado com a equipe (ver Decisões). |

## Passos técnicos realizados

- [x] **Topologia versionada** em `infra/rabbitmq/topologia.json`, no formato de definições do RabbitMQ. O `definitions.json` continua gerado e fora do git (ver Decisões).
- [x] **Exchange `notificacoes` (direct)**: `email` → `notificacoes.email`, `interna` → `notificacoes.interna`.
- [x] **DLX `notificacoes.dlx` (direct)** com `notificacoes.email.dlq` e `notificacoes.interna.dlq`, TTL de 7 dias. As filas principais têm `x-dead-letter-exchange` e `x-dead-letter-routing-key` explícitos.
- [x] **Exchange `evidencias` (direct)**: `processamento` → `evidencias.processamento`, com DLX `evidencias.dlx` → `evidencias.processamento.dlq`, sem TTL.
- [x] **Combinar com a equipe do ms-notificacoes e registrar quem aprova mudanças**: combinado. Mudança exige aprovação das duas equipes, backend e ms-notificacoes (registrado no README, seção "Topologia do RabbitMQ").
- [x] Exchange `outbox.eventos` removida do gerador.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Fonte da verdade | `topologia.json` versionado; o gerador junta o arquivo com o usuário | O `definitions.json` leva o hash da senha e é gerado a cada subida pelo `rabbitmq-init`. Versioná-lo exigiria desfazer o gerador. |
| Tipo de fila | `classic`, explícito em `x-queue-type` | Broker de nó único; `quorum` só compensa em cluster. O tipo não muda sem recriar a fila. |
| DLX das evidências | `evidencias.dlx` separada, DLQ sem TTL | A RN-57 vale só para notificações; a evidência fica em `FALHA` (RN-88). |
| DLRK explícita | `x-dead-letter-routing-key` em cada fila principal | Não depende da routing key original da mensagem para chegar à DLQ certa. |
| Routing keys | `email`, `interna`, `processamento` (não o nome da fila) | Como na task. O exemplo do README e o `OutboxTest` da outbox-01 usavam `notificacoes.interna` e foram alinhados. |
| Prova | `GenericContainer` com a mesma imagem do compose + `rabbitmqctl import_definitions` do `topologia.json` | Sem dependência nova. O caminho de boot (gerador + `rabbitmq.conf`) foi verificado no dev. |
| Aprovação de mudanças | Backend e ms-notificacoes juntos | Informado pelo usuário: a topologia já foi combinada com a equipe do ms-notificacoes. |

## Atenção ao atualizar um ambiente existente

- O `rabbitmq-init` regera o `definitions.json` na próxima subida, e as filas novas são criadas. Não há variável nova.
- **A exchange `outbox.eventos` continua nos volumes existentes** (`rabbitmq-dev`, `rabbitmq-prod`): a importação cria, mas não apaga. Ela não tem uso. Para removê-la: `rabbitmqctl delete_exchange outbox.eventos` no container do RabbitMQ.
- **Mudar um argumento de fila existente no `topologia.json` não tem efeito.** A importação termina sem erro e a fila fica como estava (verificado: TTL de 7 dias reimportado como 1 dia continuou 7 dias). É preciso apagar a fila (`rabbitmqctl delete_queue <fila>`, que descarta as mensagens dela) e subir de novo.

## Como verificar

Ver `docs/testes/outbox-02-topologia-rabbitmq.md` e a seção 7B de `docs/plano-testes-manuais.md`.
