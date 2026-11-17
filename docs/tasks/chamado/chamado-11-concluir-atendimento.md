# [chamado - 11] Concluir atendimento

> Requisitos: RF-27, RF-52, RF-57, RN-25, RN-33, RN-36, RN-72, RN-73, RN-118 · Depende de: [chamado - 08], [chamado - 10], [evidência - 02]

## Contexto

O técnico conclui o atendimento e o chamado passa a RESOLVIDO, o que cumpre o SLA de resolução. É obrigatório ter ao menos uma evidência; uma foto ainda RECEBIDA já vale. O cliente recebe um e-mail, e os gestores que ativaram a preferência recebem aviso no sino.

**Decisão pendente da task, resolvida pelo usuário:** o design exigia a descrição técnica para concluir, e a proposta (RF-27, RN-72) exigia só a evidência. Valem as duas exigências, e RF-27 e RN-72 foram alinhados no `docs/flowSLA.md`.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados/{id}/atendimento/conclusao` (TECNICO atribuído) sem evidência retorna 422 `EVIDENCIA_OBRIGATORIA` | `AtendimentoService.concluir` conta as evidências do atendimento em andamento pela porta `EvidenciasDoChamado` (todas as do atendimento, em qualquer estado, RN-73). Zero dá 422. Sem descrição técnica, 422 `DESCRICAO_TECNICA_OBRIGATORIA`. |
| A checagem da evidência e a mudança de estado acontecem na mesma transação | Um único `@Transactional`, com o chamado travado (`PESSIMISTIC_WRITE`, `ChamadoRepository.buscarParaAlterar`) do início ao fim (RN-118). |
| Atendimento CONCLUIDO, chamado RESOLVIDO com `resolvido_em` e relógio de resolução cumprido | `Atendimento.concluir` grava CONCLUIDO e `encerrado_em`. A `TransicaoChamado` leva o chamado a RESOLVIDO (a entidade grava `resolvido_em`), e `RelogiosSla.cumprir(RESOLUCAO)` cumpre o relógio. Conclusão atrasada viola o relógio, e o aviso vai para o `NotificadorViolacao`. |
| O cliente recebe o e-mail RESOLUCAO | `EventoNotificacao.Resolucao` na outbox, para o e-mail de contato da empresa (`MontadorDestinatarios.emailDoCliente`). |
| Só gestores com preferência ativa recebem CONCLUSAO; sem nenhum, não há evento | `PreferenciaNotificacaoService.gestoresComPreferencia`, restrito aos gestores ativos. Com a lista vazia, o evento não é gravado. |
| Testes de integração criados e passando | `ConclusaoAtendimentoTest` (10) e a rota no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Porta de contagem de evidências**: reaproveitada a `chamado.api.EvidenciasDoChamado`, já implementada pelo módulo evidencia (`ContagemEvidencias`). A `ContadorEvidencias` citada na task não foi criada (ver Decisões).
- [x] **`AtendimentoService.concluir` com o chamado bloqueado.**
- [x] **RESOLUCAO (e-mail) e CONCLUSAO (interna) na outbox.**
- [x] **Endpoint** `POST /api/chamados/{id}/atendimento/conclusao`, só TECNICO (`RegrasAcesso` e `@PreAuthorize`). Responde 200 com o chamado na visão do técnico.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Descrição técnica | Obrigatória para concluir, com o código novo `DESCRICAO_TECNICA_OBRIGATORIA` (422). RF-27 e RN-72 atualizados no `flowSLA.md` | Decisão do usuário, alinhando a proposta ao design. A evidência é conferida antes da descrição. |
| Porta | `EvidenciasDoChamado.contarPorAtendimento`, sem criar a `ContadorEvidencias` | A evidência-02 já tinha decidido reaproveitar essa porta e deixou o ajuste de nome para esta task. Ela serve como está. |
| Evidência de outro atendimento | Não conta | RN-71: a evidência pertence ao atendimento. Uma foto do atendimento interrompido não comprova o atual. |
| Trava | `PESSIMISTIC_WRITE` no chamado | Pedido pela task. Duas conclusões ou uma reatribuição ao mesmo tempo esperam a primeira terminar. |
| Gestor inativo com a preferência gravada | Não recebe CONCLUSAO | A preferência continua gravada; avisar quem não acessa mais o sistema não tem efeito. |
| Erro de estado | Sem atendimento EM_ANDAMENTO, 422 `TRANSICAO_INVALIDA` | Mesmo código da chamado-10 e da evidência-02. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- **O frontend precisa tratar o código novo `DESCRICAO_TECNICA_OBRIGATORIA`.**
- **Pendência da evidência-02 resolvida:** ao concluir, o atendimento sai de EM_ANDAMENTO. Com isso, a recepção de evidência deixa de aceitar foto em chamado resolvido.

## Como verificar

Ver `docs/testes/chamado/chamado-11-concluir-atendimento.md` e a seção 7K do `docs/plano-testes-manuais.md`.
