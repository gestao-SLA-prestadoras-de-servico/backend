# [outbox - 01] Criar a tabela de saída e o registro transacional

> Requisitos: RF-49, RN-46, RN-52, RN-113 · Depende de: [plataforma - 03]

## Contexto

Publicar no RabbitMQ direto da operação de negócio pode perder eventos se o broker cair ou a aplicação parar entre o commit e a publicação. Com a outbox, o evento é gravado na mesma transação da operação: ou os dois existem, ou nenhum existe. Esta task cria só a tabela e o registro; a publicação (publicador com *publisher confirms*, RN-114) e o expurgo de 7 dias ficam para tasks seguintes. As colunas `publicado_em`, `tentativas` e `ultimo_erro` já nascem para o publicador.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Rollback da operação de negócio não deixa evento na outbox | `OutboxServiceImpl.registrar` é `MANDATORY` e grava com `JdbcClient` no mesmo `DataSource`, então entra na transação JPA de quem chama e some no rollback. |
| Chamar `OutboxService.registrar` fora de transação lança exceção | `@Transactional(propagation = MANDATORY)`: `IllegalTransactionStateException`. |
| Inserir de novo um `eventoId` existente não gera erro nem duplicata | `INSERT ... ON CONFLICT (evento_id) DO NOTHING`; a linha original é mantida. |
| Um evento que vai para dois canais vira dois registros | `EventoSaida.routingKeys` aceita vários canais; o serviço grava uma linha por routing key, cada uma com `eventoId` próprio (ver Decisões). |
| Testes de integração criados e passando | `OutboxTest` (5 casos), contra Postgres real (Testcontainers). |

## Passos técnicos realizados

- [x] **Migração da tabela `outbox_eventos`** (`V6__cria_outbox_eventos.sql`): `id`, `evento_id` (único), `tipo`, `exchange`, `routing_key`, `payload jsonb`, `criado_em`, `publicado_em`, `tentativas` (default 0) e `ultimo_erro`.
- [x] **Índice parcial** `outbox_eventos_pendentes_idx` em `(criado_em) WHERE publicado_em IS NULL`.
- [x] **`OutboxService.registrar(evento)` com `propagation = MANDATORY`**: interface e `EventoSaida` em `outbox.api`, implementação em `internal`. `criado_em` vem do `Clock` injetado (RN-62).
- [x] **`INSERT ... ON CONFLICT (evento_id) DO NOTHING`**.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Branch | `feat/outbox`, empilhada sobre `fix/autenticacao-06-revisao` | A maior migração da pilha é a V5; a `develop` ainda está na V1. |
| Evento com dois canais | Uma linha por routing key. Com um canal, o `evento_id` é o do evento; com vários, cada linha usa `UUID.nameUUIDFromBytes(eventoId + ":" + routingKey)` | O `evento_id` é único, e o ms-notificacoes descarta repetições pelo `eventoId` (RN-55): o mesmo id nos dois canais faria o segundo ser descartado. O id derivado é determinístico, então a violação de SLA (RN-54) continua sem duplicar. |
| `eventoId` no payload | Acrescentado pelo serviço em cada linha | A mensagem precisa carregá-lo (RN-53), e só o serviço sabe o id de cada canal. |
| JPA ou JDBC | `JdbcClient`, sem entidade JPA | `ON CONFLICT DO NOTHING` com Hibernate exigiria `@SQLInsert` e desligar a conferência de linhas afetadas. Foge da convenção "tabela nasce com a entidade"; registrado em `docs/convencoes-banco.md`. Se o publicador precisar de entidade, ela entra na [outbox - 02]. |
| `id` | `UUID.randomUUID()` | A ordem de publicação vem de `criado_em`, não do id. O Postgres 17 não tem `uuidv7()`. |
| Nome da tabela | `outbox_eventos`, no plural | É o nome da especificação (seção 3.6). Exceção registrada em `docs/convencoes-banco.md`. |
| Payload | `Map<String, Object>` serializado com o `JsonMapper` do Spring | Mesmo formato dos `dados` da auditoria. |

## Atenção ao atualizar um ambiente existente

- A `V6` roda sozinha na próxima subida do backend. Não há variável nova.
- Nenhum módulo chama o `OutboxService` ainda; os eventos ficam pendentes até existir o publicador.

## Como verificar

Ver `docs/testes/outbox/outbox-01-tabela-saida-registro-transacional.md`.
