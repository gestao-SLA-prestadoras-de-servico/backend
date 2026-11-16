# [chamado - 06] Registrar o primeiro posicionamento

> Requisitos: RF-16, RN-23, RN-33, RN-53 · Depende de: [chamado - 01], [sla - 02], [outbox - 04]

## Contexto

O primeiro posicionamento do gestor cumpre o SLA de resposta e é enviado por e-mail ao cliente. Ele pode acontecer em qualquer estado não final, porque os eventos podem ocorrer fora de ordem: o técnico pode chegar antes.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados/{id}/posicionamento` (GESTOR) exige texto e só pode ser feito uma vez (segunda vez → 422 `POSICIONAMENTO_JA_REGISTRADO`) | `POST /api/chamados/{id}/posicionamento` com `{texto}` (`@NotBlank`, até 4000). `Chamado.posicionar` recusa a segunda vez com o código novo `POSICIONAMENTO_JA_REGISTRADO`. Estado final responde `TRANSICAO_INVALIDA`. |
| O relógio de resposta é cumprido; se já passou do prazo, fica violado e o aviso é gravado | `RelogiosSla.cumprir(RESPOSTA, agora)`. A `Violacao` devolvida vai para o `NotificadorViolacao`, que grava `VIOLACAO` na outbox. |
| O e-mail POSICIONAMENTO com o texto chega ao Mailpit (com o ms-notificacoes rodando) | O evento `EventoNotificacao.Posicionamento` vai para a outbox no canal `email`, com o e-mail de contato do cliente (`MontadorDestinatarios.emailDoCliente`) e o texto. A entrega no Mailpit depende do ms-notificacoes: **não verificado**, porque o repositório `ms-notifications` ainda só tem o commit inicial. |
| Rollback da transação não gera e-mail | O evento é gravado na transação do posicionamento (RN-52, RN-113). O teste provoca a falha depois da gravação do e-mail e confere que nada ficou: nem e-mail, nem texto, nem relógio cumprido. |
| Testes de integração criados e passando | `PosicionamentoChamadoTest` (11), mais as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`PosicionamentoService`**, nesta ordem:
  1. grava o texto e `posicionado_em`, que vem do `Clock`;
  2. `sla.cumprir(RESPOSTA)`, mais o `NotificadorViolacao`;
  3. e-mail na outbox;
  4. histórico `POSICIONAMENTO`;
  5. auditoria `PRIMEIRO_POSICIONAMENTO`.

  Tudo numa transação.
- [x] **`POST /chamados/{id}/posicionamento` com limite de tamanho do texto** (4000). Responde 200 com o chamado na visão do gestor, que passa a trazer `posicionamento` e `posicionadoEm`; a visão do cliente também os traz.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Estado final | 422 `TRANSICAO_INVALIDA` | Decisão do usuário: reaproveitar os códigos existentes. A task fala em "qualquer estado não final". |
| Código de "já registrado" | `POSICIONAMENTO_JA_REGISTRADO` (novo, 422) | Pedido pela task. Entrou em `CodigoErro` e em `docs/erros-api.md`. |
| Auditoria sem o texto | `PRIMEIRO_POSICIONAMENTO` com `{}` | RN-122: a trilha é imutável por 5 anos e não guarda texto livre. O texto fica no chamado. |
| Histórico | `POSICIONAMENTO` visível aos três perfis | O cliente recebe o texto por e-mail; mostrá-lo na linha do tempo não revela nada novo. |
| Concorrência | `@Version` do chamado | Dois posicionamentos ao mesmo tempo: o segundo falha com 409 ou recebe o 422 de "já registrado". |
| Falha simulada no teste de rollback | Trigger temporário em `chamado_historico`, criado e apagado pelo teste | Falha real depois da gravação do e-mail, sem mock: o spy de um bean transacional esbarra no proxy (achado da chamado-03). |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.
- Código de erro novo: `POSICIONAMENTO_JA_REGISTRADO` (422).
- O ms-notificacoes precisa tratar o tipo `POSICIONAMENTO` (schema em `contratos/notificacoes/`, outbox-04).

## Como verificar

Ver `docs/testes/chamado/chamado-06-primeiro-posicionamento.md` e a seção 7E do `docs/plano-testes-manuais.md`.
