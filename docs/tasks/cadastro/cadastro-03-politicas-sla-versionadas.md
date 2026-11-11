# [cadastro - 03] Cadastrar políticas de SLA versionadas

> Requisitos: RF-07, RF-11, RN-04, RN-16, RN-28, RN-29, RN-30, RN-31, RN-32, RN-121 · Depende de: [plataforma - 03], [auditoria - 01]

## Contexto

A política define os prazos dos três relógios por prioridade, em minutos de janela. Alterar uma política gera uma nova versão. A versão anterior nunca muda, para que chamados já abertos mantenham os prazos combinados. A política também define a prioridade padrão, usada enquanto o gestor não define a prioridade do chamado.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Gestor cria uma política e cria novas versões dela; não existe edição de versão | `POST /api/politicas` cria a política com a versão 1, e `POST /api/politicas/{id}/versoes` cria a versão seguinte. Não há `PUT` nem `DELETE`. A versão é imutável em três camadas: a entidade é `@Immutable` e não tem setters, e o trigger `politica_sla_versao_imutavel` rejeita `UPDATE`, `DELETE` e `TRUNCATE`, inclusive por SQL direto. |
| Política com resposta ≥ campo ou campo ≥ resolução, em qualquer prioridade, é rejeitada com 422 | `PrazosPorPrioridade.validarOrdem()` confere `Prazos.respeitaOrdem()` em ALTA e NORMAL e lança 422 `ORDEM_PRAZOS_INVALIDA`, com a prioridade na mensagem. No banco, o CHECK `politica_sla_versao_ordem_ck` é a última barreira. |
| O histórico de versões fica disponível em `GET /politicas/{id}/versoes` | `GET /api/politicas/{id}/versoes`, paginado, da mais nova para a mais antiga. Política inexistente responde 404. |
| A política padrão da seção 5.4 existe ao subir o sistema | Seed na `V8`: política `Padrão` (id `0199a000-0000-7000-8000-00000000c001`), versão 1 com alta 120/540/1080, normal 540/1620/2700 e prioridade padrão `NORMAL`. |
| Cada nova versão gera registro de auditoria | `ALTERACAO_POLITICA`, entidade `POLITICA_SLA` (id da política), dados `{versao, versaoId}`, na mesma transação (RN-120). A versão 1 também é auditada. |
| Testes unitários e de integração criados e passando | `OrdemPrazosTest` (unitário, 6) e `PoliticaTest` (integração, 14), mais as rotas novas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Migrações de `politica_sla` e `politica_sla_versao`** (`V8__cria_politica_sla.sql`). A versão tem os 6 prazos em minutos, `prioridade_padrao`, `criada_em` (vindo do `Clock`) e `criada_por` (sub do gestor). O número é UNIQUE por política.
- [x] **Validador de ordem dos prazos por prioridade**: `PrazosPorPrioridade.validarOrdem()`.
- [x] **`POST /politicas`, `POST /politicas/{id}/versoes`, `GET /politicas` e `GET /politicas/{id}/versoes`** (GESTOR), com o prefixo `/api`. A listagem traz a versão atual de cada política.
- [x] **Seed da política padrão** na própria `V8`.
- [x] **A API devolve minutos**: `prazos.alta` e `prazos.normal` com `resposta`, `campo` e `resolucao`. A conversão para dias úteis fica com o frontend.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Imutabilidade | Trigger no banco + `@Immutable` | Decisão do usuário. Mesmo modelo da auditoria (V4), mas sem exceção de retenção: a versão nunca sai. |
| Nome da política | Único e fixo; repetido responde 409 `NOME_POLITICA_JA_EXISTE` | Decisão do usuário. A versão nova traz só os prazos e a prioridade padrão. |
| Prioridades | `cadastro.api.Prioridade` com `ALTA` e `NORMAL` | Seção 5.4. Fica no `api` porque o chamado vai usar. |
| Prazos | Record `cadastro.api.Prazos` (`@Embeddable`), usado duas vezes na versão (`_alta` e `_normal`) | Uma estrutura só para o corpo, a resposta e a entidade. Valor ausente ou ≤ 0 responde 400 `VALIDACAO` (`@Positive`). |
| Versões criadas ao mesmo tempo | A política é travada (`PESSIMISTIC_WRITE`) antes de numerar a versão nova | Sem a trava, duas versões calculavam o mesmo número e uma caía em 500. O teste concorrente falhou nas 3 rodadas sem a trava. |
| Auditoria da versão 1 | Auditada como `ALTERACAO_POLITICA` | O critério pede "cada nova versão". A seed não é auditada, porque é migração e não ato de usuário. |
| Sub e papel do gestor | `@AuthenticationPrincipal(expression = "sub")` e `(expression = "papel")` | Mesmo motivo da cadastro-02: importar `UsuarioCorrente` cria ciclo entre cadastro e identidade. |
| Seed na mesma migração | Sim, na `V8` | A tabela e o seu dado inicial nascem juntos. Id fixo para os testes e o roteiro manual. |
| Ordem do histórico | `numero` decrescente por padrão | O gestor quer ver primeiro a vigente. |

## Atenção ao atualizar um ambiente existente

- A `V8` roda sozinha na próxima subida e cria a política `Padrão`. Nenhuma variável nova.
- Código de erro novo: `NOME_POLITICA_JA_EXISTE` (409).
- Corrigir uma versão errada é criar uma versão nova; nem o dono do banco altera a versão sem antes desligar o trigger.

## Como verificar

Ver `docs/testes/cadastro/cadastro-03-politicas-sla-versionadas.md` e a seção 4B do `docs/plano-testes-manuais.md`.
