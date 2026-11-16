# [cadastro - 04] Vincular política ao contrato

> Requisitos: RF-08, RN-02, RN-03, RN-05 · Depende de: [cadastro - 02], [cadastro - 03]

## Contexto

Cada contrato tem exatamente uma política vigente, e contrato sem política não recebe chamados. O contrato aponta para a política, e o chamado guarda a versão usada na abertura.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Gestor vincula ou troca a política de um contrato | `PUT /api/contratos/{id}/politica` com `{politicaId}` (GESTOR). Responde 200 com o contrato. Contrato inexistente responde 404; política inexistente, 422 `POLITICA_INEXISTENTE`. A listagem de contratos passa a trazer `politicaId`. |
| Um contrato nunca tem duas políticas ao mesmo tempo | Coluna `contrato.politica_id` (FK), não tabela N:N: o vínculo novo substitui o anterior. |
| Trocar a política só afeta chamados abertos depois | `cadastro.api.ContratoConsulta.versaoVigenteDaPolitica(contratoId)` devolve a versão mais recente da política do contrato (`PoliticaDoContrato`, com `versaoId`). O chamado guarda o `versaoId` na abertura, e a versão é imutável (cadastro-03). A troca muda só o que a consulta devolve daí em diante. |
| A troca de política gera registro de auditoria | `ALTERACAO_POLITICA`, entidade `CONTRATO` (valor novo de `EntidadeAuditada`), dados `{politicaAnterior, politicaNova}`, na mesma transação. Vincular a mesma política de novo não audita. |
| Testes de integração criados e passando | `PoliticaContratoTest` (8), mais a rota nova no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Coluna `politica_id` em `contrato`** (`V9__adiciona_politica_contrato.sql`), FK para `politica_sla`, nula até o primeiro vínculo, com índice.
- [x] **`PUT /contratos/{id}/politica` (GESTOR)**, com o prefixo `/api`.
- [x] **Consulta "versão vigente da política do contrato" na API do módulo**: `ContratoConsulta.versaoVigenteDaPolitica`. Lança `CONTRATO_SEM_POLITICA` (RN-03) quando o contrato não tem política, e `RecursoNaoEncontradoException` quando o contrato não existe.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Auditoria da troca | Entidade `CONTRATO` (nova em `EntidadeAuditada`), operação `ALTERACAO_POLITICA` | Decisão do usuário. A operação acontece sobre o contrato; a coluna é texto sem CHECK, então não precisou de migração. |
| Política inexistente no corpo | 422 `POLITICA_INEXISTENTE` (código novo) | O recurso do caminho é o contrato (404); a política é um dado do corpo, como o `CLIENTE_INEXISTENTE` no cadastro de usuário. |
| Revincular a mesma política | 200, sem auditoria | Nada mudou; registro repetido só poluiria a trilha. |
| Contrato aponta para a política, não para a versão | A consulta devolve a versão mais recente | O texto da task pede isso. Uma versão nova da política vale para os próximos chamados, sem precisar revincular. |
| Trocas simultâneas | O contrato é travado (`PESSIMISTIC_WRITE`) durante a troca | Sem a trava, a segunda troca registraria na auditoria uma `politicaAnterior` desatualizada. O vínculo em si nunca fica duplo. |
| Formato da consulta | `PoliticaDoContrato(politicaId, versaoId, numero, prioridadePadrao, alta, normal)` com `prazos(Prioridade)` | O chamado precisa dos prazos da prioridade em uso e da padrão (RN-16). |
| Desvincular | Não criado | Não pedido; RN-02 fala de trocar, não de remover. |

## Atenção ao atualizar um ambiente existente

- A `V9` roda sozinha. Contratos existentes ficam com `politica_id` nulo até o gestor vincular, e até lá não recebem chamados.
- Código de erro novo: `POLITICA_INEXISTENTE` (422). Valor novo: `EntidadeAuditada.CONTRATO`.
- O módulo chamado deve chamar `ContratoConsulta.versaoVigenteDaPolitica` na abertura e guardar o `versaoId`.

## Como verificar

Ver `docs/testes/cadastro/cadastro-04-vincular-politica-contrato.md` e a seção 4C do `docs/plano-testes-manuais.md`.
