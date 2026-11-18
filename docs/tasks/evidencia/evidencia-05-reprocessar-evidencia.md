# [evidência - 05] Reprocessar evidência com falha

> Requisitos: RF-29, RN-121 · Depende de: [evidência - 03], [outbox - 01], [auditoria - 01]

## Contexto

Uma evidência em `FALHA` (storage fora do ar além do retry, imagem que não decodificou) fica sem derivados e com a mensagem na DLQ. O gestor pode pedir o reprocessamento: a evidência volta a `RECEBIDA` e um evento novo vai para a outbox, e o consumidor da evidencia-03 a processa de novo.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /api/evidencias/{id}/reprocessamento` (GESTOR) só aceita evidência em FALHA; outros estados retornam 422 | `EvidenciaRegrasAcesso` + `@PreAuthorize("hasRole('GESTOR')")`. O `UPDATE` condicional `... where estado = FALHA` que não muda nenhuma linha vira `RegraNegocioException(REPROCESSAMENTO_NAO_PERMITIDO)` (422). Id inexistente: 404. |
| A evidência volta a RECEBIDA e um novo evento é gravado na outbox com novo eventoId | O mesmo `UPDATE` coloca `RECEBIDA` (e incrementa `version`, `updated_at` do Clock); `RecepcaoEvidenciaService.eventoProcessamento` monta o evento (`eventoId` nulo: a outbox gera um novo). |
| A auditoria registra o reprocessamento | `OperacaoAuditada.REPROCESSAMENTO`, `EntidadeAuditada.EVIDENCIA`, `chamadoId`, dados `{"de": "FALHA", "para": "RECEBIDA"}` (sem dado pessoal), na mesma transação. |
| Testes de integração criados e passando | `ReprocessamentoEvidenciaTest` (6) e um teste ponta a ponta no `ProcessadorEvidenciasTest`. |

## Passos técnicos realizados

- [x] `ReprocessamentoService.reprocessar` (`@Transactional`): lê a evidência, `UPDATE` condicional, auditoria e outbox.
- [x] `EvidenciaRepository.reprocessar` (mesmo padrão de `reivindicar`/`concluir`/`falhar`).
- [x] `RecepcaoEvidenciaService.eventoProcessamento`: a montagem do evento, num lugar só, usada pela recepção e pelo reprocessamento.
- [x] `POST /api/evidencias/{id}/reprocessamento` no `EvidenciaController`, 202 com o mesmo `EvidenciaRecebida` (`{id, estado: "RECEBIDA"}`).
- [x] `CodigoErro.REPROCESSAMENTO_NAO_PERMITIDO`, `docs/erros-api.md`, `docs/openapi.json`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Concorrência | O `UPDATE` condicional decide, não uma leitura seguida de escrita | Dois gestores ao mesmo tempo: o segundo não encontra mais `FALHA` e recebe 422; só um evento e uma auditoria. |
| Código do 422 | `REPROCESSAMENTO_NAO_PERMITIDO` (novo) | Não cabe em `TRANSICAO_INVALIDA` (é do chamado); o nome segue o de `CANCELAMENTO_NAO_PERMITIDO`. |
| Evento | Mesmo payload da recepção (`evidenciaId`, `chamadoId`, `chaveOriginal`), `eventoId` novo | O `GeradorDerivados` reivindica `RECEBIDA`/`PROCESSANDO`, então a mensagem nova é processada. |
| Mensagem antiga na DLQ | Se for reinjetada à mão depois do reprocessamento, acha a evidência `DISPONIVEL` e é descartada com ack (idempotência da evidencia-03); se ainda estiver em `FALHA`, também é descartada | Reprocessar é pelo endpoint, nunca por reinjeção. Coberto pelo teste ponta a ponta. |
| Auditoria | Só `de`/`para` | RN-122: sem dado pessoal (nem o nome do arquivo). |
| Resposta | 202 e o mesmo record da recepção | O processamento é assíncrono. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- Mensagens que já estão na `evidencias.processamento.dlq` continuam lá (não expiram); depois de reprocessar pelo endpoint, a mensagem antiga correspondente pode ser descartada do console.

## Como verificar

Ver `docs/testes/evidencia/evidencia-05-reprocessar-evidencia.md` e a seção 7J do `docs/plano-testes-manuais.md`.
