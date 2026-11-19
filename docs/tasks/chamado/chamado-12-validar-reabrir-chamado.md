# [chamado - 12] Validar ou reabrir o chamado (cliente)

> Requisitos: RF-20, RF-21, RF-32, RF-54, RN-91, RN-93 a RN-99, RN-121, RN-123 · Depende de: [chamado - 02], [sla - 04], [sla - 05], [outbox - 04]

## Contexto

Com o chamado RESOLVIDO, o cliente tem duas opções:
- **confirmar:** o chamado é encerrado e a apuração do SLA, congelada;
- **reabrir com justificativa:** o chamado volta ao mesmo técnico, ganha um atendimento novo e os prazos de campo e de resolução recomeçam.

Não existe estado REABERTO. A reabertura é um evento, registrado na auditoria.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados/{id}/validacao` (CLIENTE da empresa) leva a ENCERRADO, grava `estado_final_em` e congela a apuração | `ValidacaoService.validar`, depois `encerrar`: a `TransicaoChamado` leva a ENCERRADO (a entidade grava `estado_final_em`) e `ApuracaoSla.congelarApuracao` fotografa os relógios. Se o congelamento viola algum relógio, o aviso vai para o `NotificadorViolacao`. |
| `POST /chamados/{id}/reabertura` exige justificativa e leva a ATRIBUIDO com o mesmo técnico | `ReaberturaService.reabrir`. A justificativa é `@NotBlank`, até 1000 caracteres; vazia dá 400 `VALIDACAO`. O técnico não muda (RN-94). |
| A reabertura cria atendimento PENDENTE, reinicia campo e resolução e não mexe na resposta | `AtendimentoService.criarPendente` e `RelogiosSla.reiniciarParaReabertura` (sla-04), com a versão da política guardada no chamado e a prioridade efetiva. O ciclo 2 de CAMPO e RESOLUCAO começa na reabertura; RESPOSTA continua no ciclo 1 (RN-96, RN-97). |
| O técnico recebe REABERTURA | `EventoNotificacao.Reabertura` na outbox, para o técnico atribuído (RF-54). |
| A auditoria da reabertura responde quem, qual chamado, quando, justificativa e transição | Auditoria `REABERTURA` com `ator_sub` e `perfil` (quem), `chamado_id` e `entidade_id` (qual), `ocorrido_em` (quando) e `{justificativa, de: RESOLVIDO, para: ATRIBUIDO}`. A transição também sai como `MUDANCA_ESTADO`, pela `TransicaoChamado`. |
| Reabrir ENCERRADO retorna 422 | Fora de RESOLVIDO, 422 `REABERTURA_NAO_PERMITIDA` (RN-93, RN-99). |
| Validação, reabertura e rotina automática concorrentes: só uma vence, as outras recebem 409 | As duas rotas recebem a `version` lida. Versão diferente da atual dá 409 `CONFLITO_VERSAO`. Na corrida exata, o `@Version` do chamado barra a segunda gravação, também com 409. A rotina automática é da [chamado - 15]. |
| Testes de integração criados e passando | `ValidacaoReaberturaTest` (10) e as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`ValidacaoService`** chamando `sla.congelarApuracao`. O `encerrar` roda na transação de quem chama (`MANDATORY`), para a validação automática reaproveitá-lo.
- [x] **`ReaberturaService`**, nesta ordem:
  1. transição;
  2. `criarPendente`;
  3. `sla.reiniciarParaReabertura`;
  4. limpeza de `resolvido_em` e `aviso_enviado_em`;
  5. outbox;
  6. auditoria;
  7. histórico.

  A limpeza fica na própria transição: `Chamado.transicionar` a faz na volta RESOLVIDO → ATRIBUIDO.
- [x] **Os dois endpoints**, só CLIENTE (`RegrasAcesso` e `@PreAuthorize`). Respondem 200 com o chamado na visão do cliente.
- [x] **`version` na visão do cliente**, para o cliente enviá-la de volta.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Concorrência | `version` obrigatória no corpo das duas rotas | Decisão do usuário. Sem ela, quem chega depois da outra ação recebe 422 (o chamado já não está RESOLVIDO) e o 409 só aparece na corrida exata. É o padrão da atribuição (chamado-07). |
| Acesso | Chamado de outra empresa responde 404 | RN-91 e RN-126. O filtro é pelo `empresaClienteId` do usuário, vindo do registro local. |
| Validação fora de RESOLVIDO | 422 `TRANSICAO_INVALIDA`, pela máquina de estados | Só RESOLVIDO → ENCERRADO existe; não há código específico para validação. |
| Reabertura fora de RESOLVIDO | 422 `REABERTURA_NAO_PERMITIDA`, conferido antes da transição | A máquina aceita EM_ATENDIMENTO → ATRIBUIDO (é a reatribuição); sem a conferência, a "reabertura" de um chamado em atendimento passaria. |
| Limpeza de `resolvido_em` e `aviso_enviado_em` | Na entidade, na transição RESOLVIDO → ATRIBUIDO | A próxima resolução recomeça o prazo da validação automática e o aviso ([chamado - 15]). Ficar na transição garante que nenhum caminho esqueça. |
| Histórico | Evento novo `REABERTURA`, visível aos três perfis, com a justificativa | O cliente vê o que ele mesmo escreveu; o técnico precisa saber o motivo. |
| Justificativa na auditoria | Gravada | RN-123 a exige. É texto do cliente sobre o serviço; a tela não deve pedir dado pessoal nela. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- **A visão do cliente ganhou o campo `version`.**
- **Mudança de contrato da API:** validação e reabertura exigem `version` no corpo, e o frontend precisa enviá-la.

## Como verificar

Ver `docs/testes/chamado/chamado-12-validar-reabrir-chamado.md` e a seção 7K do `docs/plano-testes-manuais.md`.
