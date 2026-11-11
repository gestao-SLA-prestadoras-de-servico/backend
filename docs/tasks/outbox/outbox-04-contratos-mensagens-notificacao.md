# [outbox - 04] Definir os contratos das mensagens de notificação

> Requisitos: RF-52 a RF-58, RN-41, RN-53, RN-58 · Depende de: [outbox - 01]

## Contexto

O ms-notificacoes não consulta o app-principal: tudo de que ele precisa chega na mensagem. O formato precisa ser fixado num schema compartilhado. O contrato da proposta (RN-53) não tem o relógio violado nem a data do encerramento automático, e sem eles o ms-notificacoes não consegue escrever o texto da notificação.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Cada tipo de evento tem um record Java e um JSON Schema publicado no repositório | 8 records em `outbox.api.EventoNotificacao` (interface selada) e 8 schemas em `contratos/notificacoes/`, gerados dos records pelo `ContratoNotificacaoTest`. |
| Os campos `relogio` (violação) e `encerramentoPrevistoEm` (aviso de encerramento) foram combinados com a equipe do ms-notificacoes | Combinados, segundo o usuário: `relogio` = `RESPOSTA`, `ATENDIMENTO_CAMPO` ou `RESOLUCAO`; `encerramentoPrevistoEm` em ISO-8601 com fuso (`date-time`). |
| O payload nunca contém dados de contrato nem de evidência | Os records não têm esses campos, e os schemas têm `additionalProperties: false` em todos os níveis. O teste recusa payload com campo a mais e confere que nenhum schema declara `contrato` ou `evidencia`. |
| Cliente nunca é destinatário de evento interno; técnico e gestor nunca recebem e-mail | Pelo tipo: evento interno só aceita `DestinatariosInternos`, e-mail só aceita `DestinatarioEmail`, e os dois só são criados pelo `MontadorDestinatarios`. Na execução: o montador confere o perfil de cada sub e recusa cliente ou sub sem registro; o e-mail só sai de `ClienteConsulta.emailContato`. |
| Testes de contrato validam cada payload contra o schema | `ContratoNotificacaoTest` grava cada tipo pela outbox e valida o payload realmente gravado contra o schema versionado (networknt, com `format` conferido). |

## Passos técnicos realizados

- [x] **Records ATRIBUICAO, REMOCAO, REABERTURA, CONCLUSAO, VIOLACAO, POSICIONAMENTO, RESOLUCAO, AVISO_ENCERRAMENTO**: aninhados em `EventoNotificacao`. O enum `Tipo` liga cada record ao canal (`INTERNA` ou `EMAIL`).
- [x] **Campos comuns** `eventoId`, `tipo`, `chamado` (`id` e `numero`), `titulo`, `destinatarios`. Próprios: `relogio` (violação), `textoPosicionamento` (posicionamento) e `encerramentoPrevistoEm` (aviso de encerramento).
- [x] **`MontadorDestinatarios`**: `internos(subs)` e `emailDoCliente(clienteId)`. Novo `UsuarioConsulta.perfis(subs)` no identidade.
- [x] **JSON Schemas em `contratos/notificacoes/`** (draft 2020-12), um por tipo, com tudo obrigatório.
- [x] **Testes de contrato**: `ContratoNotificacaoTest` (15 casos).
- [x] `OutboxService.registrar(EventoNotificacao[, eventoId])`: exchange `notificacoes`, routing key do canal, payload = record em JSON + `eventoId`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Bibliotecas | victools `jsonschema-generator` + `jsonschema-module-jackson` 5.0.0 e networknt `json-schema-validator` 3.0.8, só em escopo de teste | As duas usam Jackson 3, como o Spring Boot 4. A 3.0.9 do networknt tinha 6 dias; a 3.0.8 é de setembro. |
| Geração dos schemas | No teste, que reescreve o arquivo e falha se mudar | Mesmo padrão do `OpenApiContratoTest`: o schema nunca diverge do record sem aparecer no diff. |
| `eventoId` e `tipo` | Fora do construtor: `tipo` vem do record, e o `eventoId` é acrescentado pela outbox | O id já era gerado na gravação (outbox-01); a violação passa o id determinístico (RN-54) na sobrecarga `registrar(evento, eventoId)`. |
| `format` no validador | `formatAssertionsEnabled(true)` | No draft 2020-12, `format` (uuid, email, date-time) é só anotação por padrão; sem isso, "amanhã" passaria como data. |
| Reatribuição | Coberta por ATRIBUICAO, sem tipo próprio | Lista da task; a RF-53 trata atribuição e reatribuição como a mesma notificação. O spec (seção 3.4) ainda cita "reatribuição" como tipo. |
| `chamado.numero` | Inteiro; o ms-notificacoes exibe `#1042` | Formatação é da apresentação. |
| Destinatários de e-mail | Lista com exatamente 1 e-mail (`maxItems: 1`) | Mesmo nome de campo nos dois canais; o cliente tem um e-mail de contato (RN-08). |
| Lugar dos contratos | `outbox.api`, com o `MontadorDestinatarios` | Chamado, SLA e rotinas vão produzir esses eventos; o `outbox` passa a depender de `cadastro.api` e `identidade.api` (sem ciclo; `ModularidadeTest` passa). |
| Perfil do destinatário | Cópia local (`UsuarioConsulta.perfis`), inclusive de usuário inativo | É filtro de destinatário, não autorização (RN-130). Excluir inativos não é pedido. |

## Atenção ao atualizar um ambiente existente

- Sem migração nem variável. Três dependências novas, só de teste.
- **Mudança de contrato**: alterar um record regenera o schema no próximo teste, que falha até o arquivo novo ser commitado. A mudança precisa ser combinada com a equipe do ms-notificacoes (fonte única, como a topologia da outbox-02).
- Nenhum módulo grava notificação ainda; o uso entra nas tasks de chamado, SLA e encerramento automático.

## Como verificar

Ver `docs/testes/outbox/outbox-04-contratos-mensagens-notificacao.md`.
