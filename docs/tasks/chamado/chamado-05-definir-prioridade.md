# [chamado - 05] Definir ou alterar a prioridade

> Requisitos: RF-15, RF-31, RN-15, RN-17, RN-18, RN-121 · Depende de: [chamado - 01], [sla - 03]

## Contexto

Só o gestor define a prioridade que vale. Ao alterar, os prazos que ainda estão correndo são recalculados; os já cumpridos ou violados ficam como estão.

**Decisão pendente da task, resolvida pelo usuário:** a prioridade **não** pode mudar com o chamado em RESOLVIDO. Ela vale em ABERTO, ATRIBUIDO e EM_ATENDIMENTO. A sugestão da task era "qualquer estado não final"; o primeiro critério de aceite foi ajustado por essa decisão.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `PUT /chamados/{id}/prioridade` (GESTOR) funciona em qualquer estado não final (ajustado: antes de RESOLVIDO) | `PUT /api/chamados/{id}/prioridade` com `{prioridade}`. Aceita ABERTO, ATRIBUIDO e EM_ATENDIMENTO; RESOLVIDO, ENCERRADO e CANCELADO respondem 422 `TRANSICAO_INVALIDA`. |
| Normal → alta com resposta já cumprida recalcula só campo e resolução | `RelogiosSla.recalcularPorPrioridade` só toca relógios que ainda correm (sla-03), a partir do marco inicial de cada um. |
| Relógio já violado não volta a "correndo" | Mesma regra: violado não é recalculado, e o estado continua VIOLADO com o vencimento original (RN-18, RN-69). |
| Mesma prioridade da atual não gera alteração nem auditoria | Compara com a prioridade **efetiva**. Se for igual, responde 200 sem gravar nada (nem a prioridade definida), sem recalcular e sem auditar. |
| Se o recálculo vencer um prazo na hora, o aviso de violação é gravado | As violações devolvidas pelo sla vão para o `NotificadorViolacao`, que grava `VIOLACAO` na outbox na mesma transação. |
| A auditoria registra a prioridade anterior e a nova | `ALTERACAO_PRIORIDADE`, entidade `CHAMADO`, com `{anterior, nova}` (as efetivas) e o gestor como ator. |
| Testes de integração criados e passando | `PrioridadeChamadoTest` (12), mais a rota no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`PrioridadeService` chamando `sla.recalcularPorPrioridade`**. Usa a versão guardada no chamado (`ContratoConsulta.versaoDaPolitica(politicaVersaoId)`), nunca a vigente do contrato (RN-05).
- [x] **Violações repassadas ao `NotificadorViolacao`**.
- [x] **`PUT /chamados/{id}/prioridade`**: só GESTOR (`RegrasAcesso` e `@PreAuthorize`). Responde 200 com o chamado na visão do gestor, com os relógios recalculados.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Estados permitidos | ABERTO, ATRIBUIDO, EM_ATENDIMENTO | Decisão do usuário: a prioridade não muda a partir de RESOLVIDO. |
| Erro fora desses estados | 422 `TRANSICAO_INVALIDA` | Decisão do usuário: reaproveita o código existente, sem código novo. |
| Mesma prioridade | Comparada com a efetiva; igual não grava nada | Decisão do usuário. Com a padrão NORMAL, escolher NORMAL não marca a prioridade como definida. |
| `version` no corpo | Não exigida | A troca é atômica na transação; duas trocas simultâneas fazem a segunda falhar por `@Version` (409). Exigir a versão vista seria regra a mais, não pedida. |
| Histórico | Evento `ALTERACAO_PRIORIDADE`, só visível ao gestor | Decisão do usuário (chamado-04). |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova nesta task.

## Como verificar

Ver `docs/testes/chamado/chamado-05-definir-prioridade.md` e a seção 7D do `docs/plano-testes-manuais.md`.
