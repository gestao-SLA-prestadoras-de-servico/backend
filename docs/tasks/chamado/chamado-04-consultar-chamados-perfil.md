# [chamado - 04] Consultar chamados com isolamento por perfil

> Requisitos: RF-03, RF-04, RF-13, RF-14, RF-19, RF-37, RN-125, RN-126 · Depende de: [chamado - 01], [sla - 02], [sla - 05]

## Contexto

Cada perfil vê só o que pode:

- o gestor vê todos os chamados;
- o cliente vê os da própria empresa;
- o técnico vê os atribuídos a ele, sem dados comerciais.

O filtro vem sempre do usuário autenticado, nunca de parâmetro da requisição. A linha do tempo do chamado vem do histórico próprio, não da auditoria.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `GET /chamados` filtra por estado, cliente, técnico, prioridade e "com violação" para o gestor | `GET /api/chamados?estado=&clienteId=&tecnicoSub=&prioridade=&comViolacao=`, paginado. A prioridade é a efetiva (`coalesce(definida, padrão)`). "Com violação" é um `@Formula` sobre `relogio_sla`. `clienteId` e `tecnicoSub` só valem para o gestor; para os outros perfis são ignorados. |
| Cliente A pedindo chamado do cliente B recebe 404 | `ConsultaChamadoService.buscarVisivel` aplica o escopo do usuário na própria consulta. Fora dele, `RecursoNaoEncontradoException` (404) em `/{id}`, `/historico` e `/sla`. |
| Técnico removido por reatribuição deixa de ver o chamado na hora | O escopo do técnico é `tecnico_sub = sub do token`, lido do banco a cada requisição. Com outro técnico gravado, o chamado some da lista e o detalhe responde 404. |
| A resposta para o técnico não contém nenhum campo de contrato ou política | Visão `ChamadoTecnico` sem `contratoId`, `politicaVersaoId` e `prioridadePadrao`, na lista e no detalhe. O teste confere que o JSON não contém "contrato" nem "politica". |
| `GET /chamados/{id}` traz os três relógios, os atendimentos e o resumo das evidências | O detalhe traz `relogios` (`RelogiosSla.relogiosAtivos`, com o estado derivado) e `atendimentos`, cada um com a contagem de evidências. A contagem vem da porta `EvidenciasDoChamado` e é 0 até o módulo evidencia existir. |
| `GET /chamados/{id}/historico` filtra os eventos pelo perfil | O gestor vê todos; o cliente e o técnico, só os marcados como visíveis para o perfil (`visivel_cliente` e `visivel_tecnico`). |
| `GET /chamados/{id}/sla` retorna a apuração para quem tem acesso | O `SlaChamadoController` passa a checar o acesso pelo chamado (`buscarVisivel`). Quem pode ver o chamado recebe a apuração, ou `apurado: false`; os demais, 404. Fecha a pendência da sla-05. |
| Testes de integração criados e passando para os três perfis | `ConsultaChamadoTest` (9). |

## Passos técnicos realizados

- [x] **`ConsultaChamadoService` aplicando o filtro do `UsuarioCorrente`**: uma `Specification` de escopo usada na listagem, na contagem, no detalhe, no histórico, no SLA e no `podeAcessar`. Papel desconhecido ou cliente sem empresa não veem nada.
- [x] **DTOs por perfil**: `ChamadoGestor`, `ChamadoCliente` (sem `tecnicoSub`) e `ChamadoTecnico` (sem contrato e sem política), sob `ChamadoView` (`oneOf` no OpenAPI).
- [x] **`GET /chamados` com paginação e ordenação por número e por prazo mais próximo**: `sort=numero` (padrão, decrescente) ou `sort=proximoVencimento`. O próximo vencimento é o menor `vence_em` dos relógios ativos que ainda correm, calculado por `@Formula`. Chamado sem prazo correndo fica por último na ordem crescente.
- [x] **`GET /chamados/contagem`**: quantidade por estado (os 6 sempre presentes), no escopo do usuário.
- [x] **`GET /chamados/{id}`, `GET /chamados/{id}/historico` e `GET /chamados/{id}/sla`**.
- [x] **`podeAcessar(usuario, chamadoId)` na API do módulo**: `chamado.api.AcessoChamado`, implementada pela `ConsultaChamadoService`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| "Com violação" e "prazo mais próximo" | `@Formula` no `Chamado`, lendo `relogio_sla` | Decisão do usuário. Filtra e ordena no banco com paginação, sem sincronizar colunas. Registrado em `convencoes-banco.md`. |
| Visibilidade do histórico | Abertura e mudança de estado: os três perfis. Prioridade: só o gestor | Decisão do usuário. |
| O que o técnico não vê | Só contrato e política; o `clienteId` fica | Decisão do usuário. |
| O que o cliente não vê | `tecnicoSub` | É um identificador interno de usuário, sem uso para o cliente. Contrato e política são da própria empresa. |
| Filtros de cliente e técnico para outros perfis | Ignorados, sem erro | RN-126: o escopo vem do token. Um parâmetro não pode ampliar nem revelar nada. |
| `/sla` sem apuração | `apurado: false` para todo perfil com acesso | Agora o acesso é decidido pelo chamado. Antes, sem a entidade, só o gestor recebia esse retorno. |
| Contagem | Uma consulta por estado | São 6 consultas simples. Marcado com `ponytail:`; vira `GROUP BY` se pesar. |
| Resumo das evidências | Porta `chamado.api.EvidenciasDoChamado`, injetada como `Optional` | O evidencia vai depender do chamado, então o chamado não pode depender dele (mesmo padrão da `ChamadosDoTecnico`). |
| Histórico sem paginação | Lista completa em ordem cronológica | A linha do tempo de um chamado é curta. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova nesta task.
- Mudou o comportamento de `GET /api/chamados/{id}/sla`: cliente e técnico com acesso ao chamado recebem `apurado: false` em vez de 404 quando não há apuração.
- O módulo evidencia deve implementar `EvidenciasDoChamado` e usar `AcessoChamado.podeAcessar` antes de servir arquivos.

## Como verificar

Ver `docs/testes/chamado/chamado-04-consultar-chamados-perfil.md` e a seção 7D do `docs/plano-testes-manuais.md`.
