# [chamado - 10] Iniciar atendimento e registrar a descrição técnica

> Requisitos: RF-22, RF-23, RN-22, RN-24 · Depende de: [chamado - 02], [sla - 02]

## Contexto

O técnico registra que chegou ao local, o que cumpre o SLA de atendimento em campo, e depois descreve o que fez. Se o chamado foi reaberto, o atendimento pendente criado na reabertura é o que começa.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados/{id}/atendimento/inicio` (TECNICO atribuído) leva o chamado de ATRIBUIDO para EM_ATENDIMENTO | `AtendimentoService.iniciar` passa pela `TransicaoChamado`, que audita `MUDANCA_ESTADO` com o técnico como ator. Fora de ATRIBUIDO → 422 `TRANSICAO_INVALIDA`. |
| Usa o atendimento PENDENTE se existir; senão cria um novo | O PENDENTE do chamado é iniciado (`Atendimento.iniciar`: EM_ANDAMENTO e `iniciado_em`). Sem ele, um atendimento novo é criado e iniciado. |
| O relógio de campo é cumprido só no primeiro início do ciclo; um novo início após reatribuição não muda nada | `RelogiosSla.cumprir(CAMPO)` registra só o primeiro evento do ciclo (sla-02). Um início atrasado viola o relógio e o aviso vai para o `NotificadorViolacao`. |
| `PUT /chamados/{id}/atendimento/descricao` só funciona com atendimento EM_ANDAMENTO | `Atendimento.descrever` recusa outros estados com 422 `TRANSICAO_INVALIDA`; sem atendimento corrente, também 422. A descrição pode ser reescrita até a conclusão. |
| Técnico não atribuído recebe 404 | O chamado só é encontrado se o `tecnico_sub` for o sub do token (RN-126). |
| Testes de integração criados e passando | `InicioAtendimentoTest` (11), mais as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`AtendimentoService.iniciar`**, que chama `sla.cumprir(CAMPO)` e repassa a violação, se houver, ao `NotificadorViolacao`.
- [x] **`AtendimentoService.registrarDescricao`** (`{descricao}` `@NotBlank`, até 4000).
- [x] **Os dois endpoints**, só para TECNICO (`RegrasAcesso` e `@PreAuthorize`). Respondem 200 com o chamado na visão do técnico.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Acesso de técnico não atribuído | 404, verificado no serviço pelo `tecnico_sub` do chamado | RN-126. O `AtendimentoService` não usa a `ConsultaChamadoService`, que já depende dele (evita ciclo entre beans). |
| Erros de estado | `TRANSICAO_INVALIDA` | Decisão do usuário: reaproveitar códigos existentes. |
| Descrição | Pode ser reescrita enquanto EM_ANDAMENTO | RF-23 fala em registrar o que foi executado; o técnico pode completar o texto antes de concluir. |
| Histórico | Só a `MUDANCA_ESTADO` | O início é a própria mudança para EM_ATENDIMENTO; a descrição aparece no atendimento, no detalhe. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- A conclusão do atendimento (task futura) parte do atendimento EM_ANDAMENTO criado aqui.

## Como verificar

Ver `docs/testes/chamado/chamado-10-iniciar-atendimento.md` e a seção 7F do `docs/plano-testes-manuais.md`.
