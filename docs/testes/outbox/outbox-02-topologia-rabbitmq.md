# Testes — [outbox - 02] Definir a topologia do RabbitMQ

> Executados em 09/11/2026, na branch `feat/outbox` (mesma da outbox-01, por decisão do usuário; alterações ainda não commitadas). Broker `rabbitmq:4.1-management-alpine`, a mesma imagem do `infra/docker-compose.yml`. As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 147 testes, 0 falhas (`TopologiaRabbitTest` 9, `TopologiaRabbitArquiteturaTest` 1 e os 137 anteriores) | ✅ |
| 2 | Mensagem fica na fila sem consumidor | `TopologiaRabbitTest.shouldKeepPublishedMessageQueuedWithoutAnyConsumer` (email, interna, processamento) | 1 mensagem na fila do canal | passou | ✅ |
| 3 | Rejeição vai para a DLQ do canal | `shouldDeadLetterRejectedMessageToChannelDlq` (`basicReject` sem requeue) | 1 mensagem na DLQ, fila de origem vazia | passou | ✅ |
| 4 | TTL das DLQs de notificação | `shouldExpireNotificationDlqMessagesAfterSevenDays` (API de gerenciamento) | `x-message-ttl` = 604800000 | passou | ✅ |
| 5 | DLQ de evidências sem TTL | `shouldKeepEvidenceDlqMessagesWithoutExpiration` | sem `x-message-ttl` | passou | ✅ |
| 6 | Backend não declara topologia | `TopologiaRabbitArquiteturaTest.shouldNotDeclareRabbitTopologyInApplication` | nenhuma dependência de `Declarable` nem dos builders | passou | ✅ |
| 7 | Quebra: sem o binding de `notificacoes.interna` | cópia, `-Dtest=TopologiaRabbitTest` | testes 2 e 3 falham para `interna` | ambos falharam | ✅ |
| 8 | Quebra: `notificacoes.email` sem DLX | idem | teste 3 falha para `email` | `ConditionTimeout` (a DLQ não recebeu) | ✅ |
| 9 | Quebra: TTL de 1 dia | idem | teste 4 falha | só as 2 execuções do teste 4 falharam | ✅ |
| 10 | Quebra: bean `Queue` com `QueueBuilder` no módulo outbox | cópia, `-Dtest=TopologiaRabbitArquiteturaTest` | teste 6 falha | `Architecture Violation` | ✅ |
| 11 | Estabilidade do `TopologiaRabbitTest` | 3 execuções seguidas | sem falha intermitente | 3 de 3 passaram | ✅ |
| 12 | Boot real no dev, com volume existente | `docker compose ... up -d --wait rabbitmq` (só `rabbitmq-init` e `rabbitmq`); `rabbitmqctl list_exchanges/list_queues/list_bindings` | as 4 exchanges, as 6 filas com os argumentos e os 6 bindings | conforme; o gerador imprimiu `Usuário configurado: app_principal` | ✅ |
| 13 | Mensagem persiste com o broker reiniciado | publicação persistente em `notificacoes`/`email` pela API de gerenciamento, `restart rabbitmq`, `list_queues` | `notificacoes.email` com 1 mensagem | `{"routed":true}`; 1 mensagem depois do restart; fila esvaziada em seguida e broker parado | ✅ |
| 14 | Mudar argumento de fila existente | container descartável: importar o `topologia.json` e, depois, uma cópia com TTL de 1 dia | descobrir o comportamento | a segunda importação termina com sucesso, mas a fila continua com 604800000 | ⚠️ ver observação 2 |

## Observações

1. **Achado (corrigido no próprio teste):** a primeira versão do `TopologiaRabbitTest` lia a contagem logo depois de publicar. A publicação é assíncrona, e na quebra do TTL o teste de enfileiramento falhou sem relação com o TTL. As leituras passaram a esperar com Awaitility (até 5 s), e a quebra foi refeita: só o teste de TTL falhou.
2. **Achado:** mudar um argumento de fila no `topologia.json` não tem efeito num broker que já tem a fila, e a importação não acusa erro. Isso está documentado no README e na task, com o procedimento de recriar a fila.
3. **Achado:** a exchange `outbox.eventos`, da versão anterior do gerador, continua no volume `rabbitmq-dev` (a importação não apaga). Não foi removida; ver "Atenção ao atualizar um ambiente existente" na task.
4. **Achado (ambiente de teste):** o primeiro container descartável do item 14 morreu porque um `docker exec` como root rodou antes do boot e criou o `.erlang.cookie` com o dono errado, o mesmo problema registrado na plataforma-02. Refeito com `-u rabbitmq`, esperando pelo log de startup.
5. **Não verificado:** o comportamento do ms-notificacoes (que ele não declara filas e consome das filas acima). Depende da outra equipe; a topologia foi combinada com ela, segundo o usuário.
6. **Não verificado:** o backend completo no dev. Só o RabbitMQ foi iniciado, porque o backend ainda não publica nem consome.
