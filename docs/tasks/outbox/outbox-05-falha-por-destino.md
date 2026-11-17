# [outbox - 05] Isolar a falha de publicação por destino

> Requisitos: RF-50, RN-48, RN-114, RN-115 · Depende de: [outbox - 03] · Altera a decisão "na primeira falha o lote para" da [outbox - 03]

## Contexto

Na [outbox - 03], qualquer falha encerrava o ciclo do publicador. Isso juntava dois casos diferentes:

| Falha | Afeta |
| --- | --- |
| Broker inacessível, exceção AMQP, confirm fora do prazo | todos os eventos |
| Mensagem devolvida (sem fila de destino) ou nack | só aquele destino |

No segundo caso, uma fila com problema segurava todas as outras. A seção 6 de `CONVENÇÕES_RABBITMQ.md` manda apagar a fila e reiniciar o broker para mudar argumentos, o que deixa uma fila ausente por um tempo. Nesse intervalo, um evento de `evidencias.processamento` devolvido atrasava também as notificações por e-mail e as internas, contra a RN-48 ("falhas em um canal não atrasam o outro").

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Uma devolução ou um nack não impede a publicação dos eventos de outros destinos | `publicar` classifica o resultado: `DESTINO_FALHOU` guarda o destino (exchange + routing key) num `Set` do ciclo e segue para o próximo evento. |
| A ordem de criação continua valendo dentro de cada destino | Os eventos seguintes do destino que falhou são pulados no ciclo, sem tentativa; saem no próximo ciclo, na ordem. |
| Broker fora continua parando o ciclo | `AmqpException`, `ExecutionException`, timeout do confirm e interrupção dão `BROKER_FALHOU`, e o ciclo termina como antes. |
| O ciclo não entra em laço quando há eventos pulados | A leitura dos lotes passou a ser por keyset: `(criado_em, id) > (último lido)`. |
| Testes de integração | `PublicadorOutboxTest`: 5 casos, 2 novos e 2 ajustados. |

## Passos técnicos realizados

- [x] **`PublicadorOutbox.publicar` devolve `Resultado`** (`PUBLICADO`, `DESTINO_FALHOU`, `BROKER_FALHOU`), em vez de `boolean`.
- [x] **`Set<String> destinosComFalha` por ciclo**: o destino é `exchange/routing_key`.
- [x] **Keyset na consulta dos pendentes**, começando na época Unix. O `OffsetDateTime.MIN` não cabe no `timestamptz`. O `order by criado_em, id` já existia e casa com o keyset.
- [x] **Log** diz se o ciclo parou ou se só o destino espera.
- [x] **Testes**: lote de 2 na classe para passar por vários lotes; `@Timeout` de 60 s em thread separada, para que um laço infinito falhe em vez de travar a suíte.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| O que é falha "do destino" | Devolução por falta de fila e nack | Os dois são respostas do broker sobre aquela mensagem ou fila. O broker está de pé e os outros destinos podem sair. |
| O que é falha "do broker" | Exceção AMQP, falha do future, confirm fora do prazo, interrupção | Os outros eventos também falhariam, cada um esperando até `espera-confirmacao` (5 s). |
| Unidade da ordem | Destino (exchange + routing key), não o evento todo | É a unidade que o consumidor vê como fila. A ordem entre filas nunca foi garantida (regra 10 da seção 5 das convenções). |
| Paginação | Keyset em vez de reler do início | Com eventos pulados, um lote cheio só de pulados seria relido para sempre. Antes isso não acontecia só porque o ciclo parava na primeira falha. |
| Quarentena (estado `DESCARTADO`) | Não criada | Exigiria coluna, migração e métrica para um caso que hoje nenhum código produz (routing key fora da topologia). A métrica `outbox_pendente_mais_antigo_idade_seconds` continua apontando o evento parado. |
| Tentativas dos pulados | Não incrementam | O evento não foi enviado; `tentativas` conta envios. |

## Atenção ao atualizar um ambiente existente

- Não há migração nem variável nova.
- **O contrato com o ms-notificacoes não muda.** Muda a seção 2.3 de `CONVENÇÕES_RABBITMQ.md`, que, segundo o próprio documento, exige aprovação das duas equipes: avise o time do ms-notificacoes.
- Um evento sem fila agora segura só o próprio destino. `tentativas` desse evento cresce a cada ciclo (5 s), como antes.

## Como verificar

Ver `docs/testes/outbox/outbox-05-falha-por-destino.md` e o passo 7C.4 de `docs/plano-testes-manuais.md`.
