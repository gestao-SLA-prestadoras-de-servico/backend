# [retencao - 01] Criar a rotina diária de retenção e limpar a outbox

> Requisitos: RF-42, RF-48, RN-108, RN-110, RN-119 · Dependências: plataforma-04 e outbox-01 · Issue #74

## Contexto

A outbox precisa manter os eventos pendentes para publicação, mas pode descartar os que já foram publicados há mais de sete dias.

| Critério | Solução |
| --- | --- |
| Agendamento diário configurável | `RotinaRetencao`, às 02:00 no fuso da aplicação por padrão. |
| Expiração e preservação de pendentes | `LimpezaOutbox` filtra por `publicado_em < corte`. Datas nulas e datas iguais ao corte não entram na seleção. |
| Independência entre passos | Cada chamada a `executarPasso` trata sua falha e devolve o controle à rotina. |
| Sem operação manual | A rotina é interna, acionada por `@Scheduled`. |
| Relógio avançado | `LimpezaOutboxTest` cobre o corte, a reexecução e a passagem por mais de um lote. |

## Passos técnicos

- Usar o `Clock` da aplicação para obter o corte do ciclo.
- Excluir até 100 registros por transação no módulo outbox.
- Registrar itens removidos, falhas e duração com a tag `passo`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Fuso | Usar `app.fuso-horario` | Segue a convenção de fuso único. |
| Instância | Usar o agendamento Spring da instância única | RN-116 dispensa trava distribuída. |
| Métricas | Contabilizar itens após cada lote retornar | Uma falha posterior não perde a contagem do que já foi concluído. |

## Atenção ao atualizar um ambiente existente

O primeiro ciclo remove eventos antigos que já estejam publicados. A propriedade Spring `app.retencao.cron` ajusta o horário. O valor `-` desativa o agendamento.

## Como verificar

Consulte [o registro de testes](../../testes/retencao/retencao-01-rotina-e-outbox.md).
