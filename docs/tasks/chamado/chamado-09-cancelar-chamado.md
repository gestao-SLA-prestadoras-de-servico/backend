# [chamado - 09] Cancelar chamado

> Requisitos: RF-18, RN-19, RN-100, RN-121 · Depende de: [chamado - 01], [chamado - 02]

## Contexto

O gestor pode cancelar um chamado antes da resolução, sempre com motivo. Chamado cancelado não tem apuração de SLA, sai da rotina de violação e passa a contar o prazo de retenção das evidências.

**Decisões pendentes da task, resolvidas:**

1. **O atendimento em andamento vira INTERROMPIDO**, como sugerido e como já previa o passo técnico.
2. **O técnico atribuído não é avisado do cancelamento.** Decisão do usuário: nenhum evento novo é criado e a ausência do aviso fica registrada aqui. Um tipo `CANCELAMENTO` exigiria um schema novo em `contratos/notificacoes/` e tratamento no ms-notificacoes.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados/{id}/cancelamento` (GESTOR) exige motivo | `POST /api/chamados/{id}/cancelamento` com `{motivo}` (`@NotBlank`, até 1000). O motivo fica em `motivo_cancelamento`. |
| Permitido só em ABERTO, ATRIBUIDO e EM_ATENDIMENTO; em RESOLVIDO retorna 422 | `CancelamentoService` responde 422 `CANCELAMENTO_NAO_PERMITIDO` (código da RN-19) em RESOLVIDO, ENCERRADO e CANCELADO. |
| `estado_final_em` é gravado | A mudança passa pela `TransicaoChamado`, e `Chamado.transicionar` grava `estado_final_em` ao entrar em CANCELADO (início dos 180 dias de retenção, RN-100). |
| A rotina de violação ignora o chamado depois do cancelamento | **Não verificável nesta task:** a rotina é a [chamado - 14]. Decisão do usuário: os relógios não mudam no cancelamento, e a rotina vai filtrar pelo estado do chamado. Ficou registrado como pendência da 14. |
| A auditoria registra o motivo | `CANCELAMENTO {motivo}`, além da `MUDANCA_ESTADO` da transição. |
| Testes de integração criados e passando | `CancelamentoChamadoTest` (9), mais a rota no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`CancelamentoService`**: valida o estado, grava o motivo, transiciona para CANCELADO, interrompe o atendimento e audita, numa transação.
- [x] **Interrupção do atendimento corrente, se houver**: `AtendimentoService.interromperCorrente`, que preserva o atendimento e as evidências.
- [x] **`POST /chamados/{id}/cancelamento`**, que responde 200 com o chamado na visão do gestor.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Aviso ao técnico | Nenhum | Decisão do usuário (ver Contexto). |
| Rotina de violação | Filtrará pelo estado do chamado na [chamado - 14]; relógios intactos | Decisão do usuário. |
| Código de erro | `CANCELAMENTO_NAO_PERMITIDO` | Decisão do usuário: reaproveitar o existente, que é o da RN-19. |
| Motivo na auditoria | Gravado | O critério de aceite pede. É um texto do gestor, não um dado do cliente. |
| Apuração | Não é congelada | RN-19: `/sla` responde `apurado: false` para o cancelado. |
| Histórico | Só a `MUDANCA_ESTADO`, visível aos três perfis | O motivo fica no chamado e na auditoria. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- **Pendência para a [chamado - 14]:** a rotina de 15 minutos deve ignorar chamados ENCERRADO e CANCELADO, porque os relógios de um cancelado continuam ativos em `relogio_sla`.

## Como verificar

Ver `docs/testes/chamado/chamado-09-cancelar-chamado.md` e a seção 7F do `docs/plano-testes-manuais.md`.
