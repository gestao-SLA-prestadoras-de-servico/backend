# [chamado - 07] Atribuir e reatribuir o técnico

> Requisitos: RF-17, RF-53, RF-55, RN-20, RN-21, RN-22, RN-26, RN-35, RN-37, RN-117, RN-121 · Depende de: [chamado - 01], [chamado - 02], [autenticação - 05], [outbox - 04]

## Contexto

O gestor define ou troca o técnico do chamado. Atribuir não cumpre nenhum SLA, e trocar de técnico não reinicia prazos: o SLA é compromisso da prestadora, não do técnico. Na reatribuição durante o atendimento, o chamado volta para ATRIBUIDO e o atendimento atual é interrompido.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| ABERTO → ATRIBUIDO; em ATRIBUIDO só troca o técnico; em EM_ATENDIMENTO volta para ATRIBUIDO e interrompe o atendimento | `AtribuicaoService.atribuir`. ABERTO e EM_ATENDIMENTO passam pela `TransicaoChamado`, que audita `MUDANCA_ESTADO`. Na troca, `AtendimentoService.interromperCorrente`. |
| Em RESOLVIDO ou estado final retorna 422 | 422 `TRANSICAO_INVALIDA` (RN-20). |
| Técnico inativo, sem papel de técnico ou igual ao atual retorna 422 | 422 `REATRIBUICAO_NAO_PERMITIDA`. O técnico é conferido por `UsuarioConsulta.tecnicoAtivo` (novo), que lê a cópia local; é filtro de negócio, não autorização (RN-130). |
| Duas atribuições concorrentes: a segunda recebe 409 | O corpo traz a `version` lida. Se for diferente da atual, `ObjectOptimisticLockingFailureException`. Se as duas leram a mesma versão, o `@Version` barra a segunda no commit. Nos dois casos, 409 `CONFLITO_VERSAO`. |
| Nenhum relógio é alterado | A atribuição não chama o sla (RN-22, RN-26). O teste compara as linhas de `relogio_sla`, inclusive a `version`, antes e depois. |
| O novo técnico recebe ATRIBUICAO; na reatribuição, o anterior recebe REMOCAO | `EventoNotificacao.Atribuicao` para o novo (RN-35) e `Remocao` para o anterior (RN-37), na outbox, canal `interna`. |
| Fotos já enviadas continuam no atendimento interrompido | O atendimento só muda para INTERROMPIDO e nunca é apagado (chamado-02). As evidências ainda não existem; o teste prova que a linha do atendimento fica. |
| Testes de integração criados e passando | `AtribuicaoChamadoTest` (10) e `TransferenciaTecnicoTest` (2), mais as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`AtribuicaoService`**, com a troca de técnico em `trocarTecnico`, compartilhada com a transferência.
- [x] **`POST /chamados/{id}/atribuicao` recebendo `tecnicoSub` e a `version` lida**: `POST /api/chamados/{id}/atribuicao` (GESTOR). A visão do gestor passa a trazer `version`.
- [x] **Eventos ATRIBUICAO e REMOCAO na outbox.**
- [x] **Auditoria de atribuição ou reatribuição e item de histórico**:
  - primeira atribuição: `ATRIBUICAO {tecnico}`;
  - troca: `REATRIBUICAO {anterior, novo}`.

  O histórico tem os mesmos eventos, visíveis ao gestor e ao técnico.
- [x] **Carga de técnicos em `GET /tecnicos`** ([autenticação - 05]). A porta `identidade.api.ChamadosDoTecnico` passou a ser implementada pelo chamado (`TransferenciaChamados`):
  - `contarNaoFinalizados` conta ATRIBUIDO, EM_ATENDIMENTO e RESOLVIDO;
  - `transferir` aplica a RN-134 e a RN-135, o que adianta a [chamado - 13].

  O identidade não mudou: a inativação e o `GET /tecnicos` já usavam a porta.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Códigos de erro | `TRANSICAO_INVALIDA` para o estado; `REATRIBUICAO_NAO_PERMITIDA` para o técnico inválido | Decisão do usuário: reaproveitar os existentes. |
| Carga de `GET /tecnicos` | Implementar a porta `ChamadosDoTecnico` inteira, inclusive `transferir` | Decisão do usuário. Com a porta presente, a inativação passa a exigir substituto quando o técnico tem chamados e transfere de verdade. |
| Transferência por inativação | ATRIBUIDO troca o técnico; EM_ATENDIMENTO volta a ATRIBUIDO e interrompe o atendimento; RESOLVIDO só troca o técnico. Auditoria `TRANSFERENCIA_POR_INATIVACAO` por chamado; `ATRIBUICAO` ao substituto, sem `REMOCAO` ao inativado | RN-134 e RN-135. Roda na transação da inativação (`MANDATORY`). |
| Porta numa classe própria | `TransferenciaChamados`, separada do `AtribuicaoService` | Os testes do identidade substituem a porta por `@MockitoBean`. Com a porta no `AtribuicaoService`, o mock substituía o serviço inteiro e o contexto não subia. |
| Atendimento na atribuição | A atribuição não cria atendimento. A reatribuição interrompe o corrente, PENDENTE ou EM_ANDAMENTO | O início ([chamado - 10]) usa o PENDENTE ou cria um. Um PENDENTE vindo de reabertura é do técnico anterior. |
| Histórico | `ATRIBUICAO`, `REATRIBUICAO` e `TRANSFERENCIA_POR_INATIVACAO` visíveis ao gestor e ao técnico, não ao cliente | A troca de técnico é organização interna da prestadora (RN-22). A mudança de estado continua visível a todos. |
| Resposta | 200 com o chamado na visão do gestor | O frontend atualiza a tela e a `version` sem outra chamada. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- **A inativação de técnico muda de comportamento:** com chamados não finalizados, ela agora exige substituto (`SUBSTITUTO_OBRIGATORIO`) e transfere os chamados. Até aqui a porta estava ausente e a carga era sempre 0.
- O ms-notificacoes precisa tratar `ATRIBUICAO` e `REMOCAO` (schemas da outbox-04).

## Como verificar

Ver `docs/testes/chamado/chamado-07-atribuir-reatribuir.md` e a seção 7E do `docs/plano-testes-manuais.md`.
