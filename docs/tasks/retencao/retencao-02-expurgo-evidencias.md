# [retencao - 02] Expurgar evidências vencidas

> RF-43, RF-45 e RN-100 a RN-105 · Dependências: retenção-01, evidência-01 e auditoria-01 · Issue #75

## Contexto

O conteúdo fotográfico tem prazo de retenção de 180 dias a partir do estado final do chamado. A evidência continua consultável pelos metadados após o expurgo.

| Critério | Solução |
| --- | --- |
| Só estados finais com mais de 180 dias | A API `ChamadosParaRetencao` exige `ENCERRADO` ou `CANCELADO` e `estado_final_em` anterior ao corte. |
| Chamado resolvido permanece | `RESOLVIDO` fica fora da seleção, qualquer que seja sua data. |
| Prefixo vazio | O adaptador remove todas as chaves sob o prefixo da evidência, inclusive derivados sem registro. |
| Rastreabilidade | A entidade passa a `EXPURGADA` e recebe `expurgadaEm`. Os metadados existentes permanecem. |
| Auditoria | Registra `EXPURGO_EVIDENCIA` com ator `SISTEMA` e motivo `EXPIRACAO_RETENCAO` na transação que muda o estado. |
| Reexecução após falha | A remoção de prefixo vazio é segura. Um rollback permite repetir o lote mesmo se o MinIO já tiver retirado os objetos. |

## Passos técnicos

- Percorrer candidatas por cursor de UUID, em transações de até 100 evidências.
- Remover o prefixo do MinIO, atualizar a entidade e gravar a auditoria.
- Registrar itens, falhas e duração na etapa `evidencias`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Concorrência com processamento | O gerador e o expurgo usam a mesma trava de linha durante o acesso ao MinIO | Impede a recriação de derivados após o expurgo, inclusive quando chega uma mensagem atrasada. |
| Falha parcial | Remover os objetos antes da atualização no PostgreSQL | A evidência só é marcada depois da remoção. Se o banco falhar, a próxima execução repete o lote. |

## Atenção ao atualizar um ambiente existente

O primeiro ciclo alcança evidências cujo prazo já venceu. Use `app.retencao.cron` para agendar esse ciclo.

## Como verificar

Consulte o [registro dos testes](../../testes/retencao/retencao-02-expurgo-evidencias.md).
