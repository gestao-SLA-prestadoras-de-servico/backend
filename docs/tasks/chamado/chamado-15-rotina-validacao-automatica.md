# [chamado - 15] Rotina de validação automática e aviso prévio

> Requisitos: RF-35, RN-33, RN-34, RN-92, RN-119, RN-121 · Depende de: [chamado - 12], [sla - 01], [sla - 05]

## Contexto

Um chamado RESOLVIDO sem resposta do cliente por 3 dias úteis é encerrado automaticamente. Um dia útil antes, o cliente recebe um aviso por e-mail. A task deixava duas decisões em aberto:
- a frequência da rotina;
- o que fazer quando o aviso não saiu no horário, por exemplo com o sistema fora do ar.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Limite = `resolvido_em` + 1620 minutos de janela; aviso = `resolvido_em` + 1080 minutos de janela | `RotinaValidacaoAutomatica` calcula os dois pelo `CalendarioJanela.adicionarMinutos` (3 e 2 dias úteis de 540 minutos). |
| Resolvido segunda 10:00 → aviso quarta ~10:00 e encerramento quinta ~10:00 (com execução horária) | Cron `0 0 * * * *`. `ValidacaoAutomaticaTest.shouldWarnOnWednesdayAndCloseOnThursdayRunningHourly` roda a rotina de hora em hora, de segunda 11:00 a sexta 12:00: o aviso sai na quarta 10:00 e o encerramento acontece na quinta 10:00. |
| O aviso `AVISO_ENCERRAMENTO` sai uma única vez por resolução | Só sai com `aviso_enviado_em` nulo, e a rotina o grava no mesmo lote. O `eventoId` é determinístico, `nameUUID("aviso-encerramento:" + chamado + ":" + resolvido_em)`. |
| Reabertura seguida de nova resolução gera novo aviso | A reabertura limpa `resolvido_em` e `aviso_enviado_em` ([chamado - 12]). A nova resolução grava outro `resolvido_em`, e com ele o `eventoId` também muda. |
| O encerramento grava `estado_final_em`, congela a apuração e audita como "validação automática", com ator SISTEMA | Reaproveita o `ValidacaoService.encerrar(chamado, Ator.SISTEMA)`: `TransicaoChamado` para ENCERRADO (grava `estado_final_em` e `MUDANCA_ESTADO`) e `ApuracaoSla.congelarApuracao`. Em seguida grava a auditoria `VALIDACAO_AUTOMATICA` com `{resolvidoEm, avisoEnviadoEm}`. |
| Conflito com validação ou reabertura do cliente pula o registro | O lote é lido com `PESSIMISTIC_WRITE` e `SKIP LOCKED`: o chamado que o cliente está validando ou reabrindo fica para a passada seguinte. Se o cliente leu antes e grava depois da rotina, recebe 409 pela `version` (chamado-12). |
| Testes de integração com relógio avançado criados e passando | `ValidacaoAutomaticaTest` (7). |

## Passos técnicos realizados

- [x] **`RotinaValidacaoAutomatica`** com `@Scheduled(cron = "${app.chamado.validacao-automatica.cron}")`, valor `0 0 * * * *` e `-` nos testes.
- [x] **`AVISO_ENCERRAMENTO`** gravado com `eventoId` = chamado + `resolvido_em`, para o e-mail de contato da empresa (`MontadorDestinatarios.emailDoCliente`), com `encerramentoPrevistoEm`. Depois a rotina marca `aviso_enviado_em` (`Chamado.registrarAviso`).
- [x] **Encerramento** pelo `ValidacaoService.encerrar`.
- [x] **Lotes** de 100, uma transação cada (`TransactionTemplate`), lidos por `ChamadoRepository.travarResolvidos` com cursor keyset `(resolvido_em, id)`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Frequência | De hora em hora (`0 0 * * * *`) | Decisão do usuário. Uma rotina diária atrasaria o aviso e o encerramento em até 1 dia. |
| Aviso atrasado (sistema fora) | Avisa assim que puder e adia o encerramento para 1 dia útil depois do aviso: encerra quando `agora >= max(limite, aviso_enviado_em + 540 min)` | Decisão do usuário. O cliente sempre tem 1 dia útil entre o aviso e o encerramento (RN-34). O `encerramentoPrevistoEm` do e-mail usa a mesma conta. |
| Nunca encerrar sem aviso | Sem `aviso_enviado_em`, a rotina só avisa, mesmo depois do limite | Consequência da decisão acima. |
| Cron em qualquer horário | Roda também fora da janela, sem filtro de horário | Os marcos sempre caem dentro da janela. Fora dela, a rotina só age se o sistema esteve fora no horário do marco. |
| Conflito com o cliente | `SKIP LOCKED` no lote | O mesmo motivo da [chamado - 14]: numa transação por lote, uma falha de `@Version` derrubaria o lote inteiro. |
| Auditoria | `VALIDACAO_AUTOMATICA` (RN-121), além do `MUDANCA_ESTADO` da transição | A operação já existia em `OperacaoAuditada`. Os dados (`resolvidoEm`, `avisoEnviadoEm`) mostram por que o chamado foi encerrado. |

## Atenção ao atualizar um ambiente existente

- **Nenhuma migração nem variável de ambiente nova.** A propriedade `app.chamado.validacao-automatica.cron` tem valor no `application.properties`.
- **Na primeira passada,** chamados RESOLVIDO há mais de 2 dias úteis recebem o aviso. Nenhum é encerrado sem aviso: o encerramento vem 1 dia útil depois dele.
- **O aviso muda a `version` do chamado.** O cliente que estava com a tela aberta recebe 409 ao validar ou reabrir e precisa recarregar.

## Como verificar

Ver `docs/testes/chamado/chamado-15-rotina-validacao-automatica.md` e a seção 7L do `docs/plano-testes-manuais.md`.
