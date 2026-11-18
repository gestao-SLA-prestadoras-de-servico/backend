# [chamado - 08] Ativar a preferência de notificação do gestor

> Requisitos: RF-56, RN-36, RN-40, RN-121 · Depende de: [chamado - 01]

## Contexto

Cada gestor decide, chamado por chamado, se quer ser avisado quando o técnico concluir o atendimento. A preferência de um gestor não afeta a dos outros.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `PUT` e `DELETE /chamados/{id}/preferencia-notificacao` (GESTOR) ativam e desativam para o próprio gestor | `PUT` e `DELETE /api/chamados/{id}/preferencia-notificacao`, 204. O gestor vem sempre do sub do token; não há parâmetro de gestor. Chamado inexistente → 404. |
| Ativar duas vezes não gera erro nem duplicata | A segunda ativação responde 204 sem gravar nada. A UNIQUE `(chamado_id, gestor_sub)` barra a duplicata, até em dois cliques simultâneos. |
| Gestor A ativar não afeta o gestor B | Uma linha por gestor e chamado; desativar remove só a do gestor do token. |
| O detalhe do chamado informa se o gestor corrente ativou | `GET /api/chamados/{id}` traz `notificarConclusao` na visão do gestor. O campo não vai na listagem nem para cliente e técnico. |
| Cada alteração gera auditoria | `ALTERACAO_PREFERENCIA_NOTIFICACAO`, entidade `PREFERENCIA_NOTIFICACAO` (id do chamado), dados `{ativa}`. Repetição sem mudança não audita. |
| Testes de integração criados e passando | `PreferenciaNotificacaoTest` (5), mais as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Migração da tabela `preferencia_notificacao`** (`V14__cria_preferencia_notificacao.sql`): `chamado_id` (FK), `gestor_sub` e UNIQUE `(chamado_id, gestor_sub)`. A linha existe enquanto a preferência está ativa.
- [x] **Endpoints usando sempre o sub do token.**
- [x] **Consulta interna `gestoresComPreferencia(chamadoId)`** em `PreferenciaNotificacaoService`, para a conclusão do atendimento (task futura).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Modelo | Linha presente = ativa; desativar apaga a linha | Não há outro estado a guardar; a trilha das mudanças fica na auditoria (RN-121). |
| Sem `updated_at` | A entidade não estende `EntidadeBase` | A linha nunca é alterada, só criada e removida. |
| Repetição | 204 sem auditoria | Nada mudou; registro repetido só poluiria a trilha (como o revínculo de política da cadastro-04). |
| Entidade auditada | `PREFERENCIA_NOTIFICACAO` com o id do chamado | A preferência não tem id estável (a linha some ao desativar); o chamado identifica, e o ator diz de qual gestor. |
| Estado do chamado | Aceita em qualquer estado | A task não restringe; em estado final a preferência apenas não terá efeito. |
| Histórico | Não gera item | É uma configuração pessoal do gestor, não um evento do chamado. |

## Atenção ao atualizar um ambiente existente

- A `V14` roda sozinha. Nenhuma variável nova.
- A conclusão do atendimento (task futura) deve usar `gestoresComPreferencia` para o aviso `CONCLUSAO` (RN-36).

## Como verificar

Ver `docs/testes/chamado/chamado-08-preferencia-notificacao.md` e a seção 7F do `docs/plano-testes-manuais.md`.
