# [retencao - 03] Expurgar auditoria com mais de cinco anos

> Requisitos: RF-44, RN-106, RN-107 · Dependências: retenção-01 e auditoria-01 · Issue #76

## Contexto

A rotina diária remove a trilha cujo período de retenção terminou. O banco mantém a proteção da auditoria por trigger.

| Critério | Solução |
| --- | --- |
| Registros vencidos removidos em lotes | `ExpurgoAuditoria` exclui até 100 registros em cada transação. |
| Registros dentro da retenção permanecem | O corte é estrito e segue o calendário da sessão do banco. O trigger valida cada exclusão. |
| Contagem em métrica e log | A rotina reutiliza a instrumentação da retenção com `passo=auditoria`. |

## Passos técnicos

- Adicionar a operação de lote à API do módulo de auditoria e acioná-la na rotina diária.
- Cobrir a fronteira de cinco anos, o isolamento de falhas e o bloqueio do trigger com PostgreSQL descartável.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Corte | Usar o menor instante entre o início do ciclo e o relógio da transação, menos cinco anos de calendário | O lote respeita o relógio injetado e o limite que o trigger do banco aplicará. |
| Proteção | Reutilizar o trigger da V4 | A proteção existente permanece ativa para SQL direto. |

## Atenção ao atualizar um ambiente existente

A rotina alcança a trilha vencida no primeiro ciclo. Para controlar quando vai rodar, use `app.retencao.cron`.

## Como verificar

Consulte [o registro de testes](../../testes/retencao/retencao-03-expurgo-auditoria.md).
