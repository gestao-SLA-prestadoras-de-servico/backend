# [chamado - 14] Rotina de detecção de violação (a cada 15 minutos)

> Requisitos: RF-33, RF-34, RF-58, RN-39, RN-54, RN-65, RN-66, RN-67, RN-116, RN-119 · Depende de: [chamado - 01], [sla - 02]

## Contexto

A violação precisa ser percebida sem ninguém reclamar. Até aqui, um relógio vencido só era violado quando alguém o tocava (cumprimento, recálculo, reabertura ou congelamento). A cada 15 minutos, a rotina marca os prazos vencidos com o horário real do estouro e avisa todos os gestores e o técnico atribuído. Ela fica no módulo chamado porque precisa do estado do chamado e do sla.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Relógios vencidos, sem cumprimento e sem violação, de chamados não finais (inclusive RESOLVIDO), são marcados com `violado_em = vence_em` | `RelogiosSla.travarVencidos` lê os relógios ativos, sem `cumprido_em` e sem `violado_em`, com `vence_em < agora` (no vencimento exato ainda está no prazo, RN-65). A `RotinaViolacao` pula ENCERRADO e CANCELADO e chama `RelogiosSla.marcarViolacao`, que reaproveita `RelogioSla.marcarViolacao`: `violado_em = vence_em` (RN-67). |
| Rodar a rotina duas vezes seguidas não gera segundo aviso | O relógio violado sai da consulta. Além disso, `marcarViolacao` devolve vazio se já estava marcado, e o `eventoId` do aviso é determinístico (RN-54). |
| Chamado sem técnico avisa só os gestores | O aviso passa pelo `NotificadorViolacao`, que já monta gestores ativos + técnico, se houver (RN-39). |
| Conflito de versão com uma ação do usuário pula o registro, que volta no ciclo seguinte | O lote é lido com `PESSIMISTIC_WRITE` e `SKIP LOCKED`: o relógio que uma transação do usuário está alterando não vem no lote e volta na passada seguinte. Se o usuário leu o relógio antes e grava depois da rotina, quem recebe 409 é ele, pelo `@Version` do relógio. |
| O processamento é feito em lotes, com uma transação por lote | Lotes de 100, cada um num `TransactionTemplate` (RN-119). O cursor keyset `(vence_em, id)` avança sobre os relógios pulados (chamados finalizados), para não relê-los no mesmo ciclo. |
| Teste com relógio avançado cobre os três relógios | `RotinaViolacaoTest.shouldMarkEachClockWithItsDueTimeAsTimeAdvances` avança o `RelogioMutavel` até o vencimento de cada relógio (nada muda) e 1 minuto depois (só ele é marcado), na ordem RESPOSTA, CAMPO e RESOLUCAO. |

## Passos técnicos realizados

- [x] **Consulta de relógios vencidos na API do sla:** `RelogiosSla.travarVencidos(agora, aposVenceEm, aposId, limite)`, que devolve `RelogioVencido(id, chamadoId, venceEm)`, e `RelogiosSla.marcarViolacao(relogioId)`. A consulta usa o índice parcial `relogio_sla_correndo_idx`, criado na V10 para esta rotina. Nenhuma migração nova.
- [x] **`RotinaViolacao`**, com `@Scheduled(cron = "${app.chamado.rotina-violacao.cron}")`, valor `0 */15 * * * *` no `application.properties` e `-` (desligado) nos testes.
- [x] **`sla.marcarViolacao` + `NotificadorViolacao` por registro.**
- [x] **Histórico e métrica:** item `VIOLACAO_SLA` (ator SISTEMA, `{relogio, ciclo, violadoEm}`) e contador `sla.violacoes` com a tag `relogio`, que no Prometheus aparece como `sla_violacoes_total{relogio="..."}`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Chamados ENCERRADO e CANCELADO | A rotina filtra pelo estado do chamado; os relógios não mudam | Pendência registrada na [chamado - 09]: os relógios do cancelado continuam ativos, por decisão do usuário. |
| Conflito com o usuário | `SKIP LOCKED` no lote, em vez de captura de exceção por registro | Numa transação por lote, uma falha de `@Version` derrubaria o lote inteiro. Com a trava, a rotina nunca perde: o que está em uso é pulado e volta em 15 minutos. |
| Cursor | Keyset `(vence_em, id)` | Os relógios de chamados finalizados continuam vencidos; sem cursor, um lote cheio deles seria relido para sempre. É o mesmo padrão do `PublicadorOutbox`. |
| Tamanho do lote | Constante de 100 na classe; os testes chamam `executar(int)` pelo `ChamadoTestHelper` | O valor não muda por ambiente; propriedade só para teste seria configuração sem uso. |
| Histórico `VIOLACAO_SLA` | Visível aos três perfis | Cliente e técnico já veem os relógios e o `comViolacao` do chamado. |
| Cron configurável | `app.chamado.rotina-violacao.cron` | Para desligar nos testes (`-`), em que o horário real não pode disparar a rotina. |

## Atenção ao atualizar um ambiente existente

- **Nenhuma migração nem variável de ambiente nova.** A propriedade `app.chamado.rotina-violacao.cron` tem valor no `application.properties`.
- **Na primeira passada,** a rotina marca de uma vez todos os relógios que venceram enquanto ela não existia. Cada um gera um aviso `VIOLACAO` para os gestores e o técnico. Num ambiente com muitos chamados antigos abertos, isso pode ser uma rajada de notificações.

## Como verificar

Ver `docs/testes/chamado/chamado-14-rotina-deteccao-violacao.md` e a seção 7L do `docs/plano-testes-manuais.md`.
