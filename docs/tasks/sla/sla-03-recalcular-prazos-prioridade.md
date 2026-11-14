# [sla - 03] Recalcular prazos quando a prioridade muda

> Requisitos: RF-31, RN-17, RN-18 · Depende de: [sla - 02]

## Contexto

Quando o gestor define ou altera a prioridade, os prazos que ainda estão correndo são recalculados a partir do marco inicial de cada relógio. Prazos já cumpridos ou já violados não mudam.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Só relógios ativos, sem cumprimento e sem violação, são recalculados | `RelogioSla.correndo()` define o estado recalculável. O serviço só recalcula esses, e a entidade lança `IllegalStateException` se alguém tentar recalcular outro. |
| O recálculo usa a versão da política gravada no chamado, não a vigente | `recalcularPorPrioridade(chamadoId, versao, novaPrioridade)` usa a versão que o chamador passa; o sla não busca versão nenhuma. O chamado obtém a versão guardada com `ContratoConsulta.versaoDaPolitica(versaoId)` ([sla - 02]). |
| Após uma reabertura, o recálculo usa o marco da reabertura | O vencimento novo é `adicionarMinutos(marcoInicial do próprio relógio, prazo novo)`. Depois da reabertura, o relógio ativo de CAMPO e RESOLUCAO é o do ciclo novo, cujo marco é a reabertura ([sla - 04]). |
| Se o novo prazo já ficou no passado, a violação é marcada na hora e devolvida ao chamador | `RelogioSla.recalcular` chama `marcarViolacao()` quando o novo vencimento é anterior a agora (`Clock`). O método devolve `List<Violacao>`. |
| Testes unitários criados e passando | 3 casos novos em `RelogioSlaTest` e 4 em `RelogiosSlaTest`, mais 1 de recálculo depois da reabertura. |

## Passos técnicos realizados

- [x] **`recalcularPorPrioridade(chamadoId, versaoPolitica, novaPrioridade)`** em `RelogiosSla`, com `propagation = MANDATORY`.
- [x] **Reutilizar `marcarViolacao` para prazos que já venceram**: `RelogioSla.recalcular` chama o mesmo método do cumprimento atrasado.
- [x] **Retornar a lista de violações marcadas**: `List<Violacao>`, com o vencimento novo como horário do estouro.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| "Prazo no passado" | Vencimento novo anterior a agora; igual a agora ainda não viola | Mesma regra do cumprimento: violação é o prazo ultrapassado (RN-65). |
| Horário da violação | O vencimento novo | RN-67: o horário real do estouro, que passou a ser o novo vencimento. |
| Relógio vencido, mas não marcado pela rotina | Continua "correndo" e é recalculado | Até a marcação, a violação não existe para o sistema. Se o novo prazo também já passou, ela é marcada com o vencimento novo. |
| Prioridade igual à atual | Recalcula do mesmo jeito | O resultado é o mesmo prazo; não vale um caminho especial. |

## Atenção ao atualizar um ambiente existente

- Sem migração nem variável nova.
- O chamado deve chamar `recalcularPorPrioridade` na mesma transação da alteração de prioridade, com a versão guardada no chamado, e gravar na outbox cada violação devolvida.

## Como verificar

Ver `docs/testes/sla/sla-03-recalcular-prazos-prioridade.md`.
