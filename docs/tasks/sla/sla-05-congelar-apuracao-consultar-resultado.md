# [sla - 05] Congelar a apuração e consultar o resultado

> Requisitos: RF-36, RF-37, RN-19, RN-68 · Depende de: [sla - 02]

## Contexto

No encerramento, o resultado dos três SLAs é fotografado e nunca mais muda. É essa fotografia que o relatório gerencial usa. Chamado cancelado não tem apuração.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `congelarApuracao(...)` grava, por relógio, prazo, vencimento, cumprimento, violação e resultado, além de cliente, prioridade e técnico responsável | `ApuracaoSla.congelarApuracao(chamadoId, clienteId, prioridade, tecnicoSub)` grava uma linha por tipo em `apuracao_sla`, com os dados do relógio do último ciclo e os dados do chamado recebidos do chamador. |
| Não existe atualização de uma apuração gravada | A entidade `ApuracaoRelogio` é `@Immutable`, não há método de alteração, e o trigger `apuracao_sla_imutavel` rejeita `UPDATE`, `DELETE` e `TRUNCATE`. Congelar de novo não faz nada. |
| Alterar a política depois não muda a apuração | A apuração guarda os prazos e vencimentos, não aponta para a versão da política. O teste cria uma versão nova da política depois e compara as linhas. |
| `GET /chamados/{id}/sla` retorna o resultado para quem tem acesso ao chamado; cancelado retorna "sem apuração" | `GET /api/chamados/{id}/sla`, no módulo chamado. Gestor vê qualquer chamado; cliente, os da própria empresa; técnico, os que encerrou como responsável. Sem apuração, o gestor recebe `{"apurado": false}`. |
| Testes de integração criados e passando | `ApuracaoSlaTest` (9) e um caso novo no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Migração da tabela `apuracao_sla`** (`V11__cria_apuracao_sla.sql`):
  - uma linha por relógio, com UNIQUE `(chamado_id, tipo)`;
  - FK para `cliente`;
  - CHECK de que CUMPRIDO não tem violação nem violação em ciclo anterior;
  - índices por cliente e por técnico, para o relatório;
  - trigger de imutabilidade.
- [x] **`congelarApuracao(chamadoId, clienteId, prioridade, tecnicoSub)`** (dados recebidos do chamador), com `propagation = MANDATORY`. Devolve as violações marcadas no congelamento.
- [x] **Indicador "houve violação em ciclo anterior"**: `violou_em_ciclo_anterior`.
- [x] **`consultarApuracao(chamadoId)` na API do módulo**: `Optional<ApuracaoChamado>`.
- [x] **Endpoint `GET /chamados/{id}/sla` no módulo chamado**: `chamado.internal.SlaChamadoController`, com `ChamadoRegrasAcesso` e `@PreAuthorize` para os três perfis. O sla não depende do chamado (`SlaArquiteturaTest`).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Crédito no relatório por técnico (decisão pendente da task) | O técnico responsável no encerramento (`tecnico_sub`) | Sugestão da task. O sla só grava o que o chamador informa. |
| Relógio sem evento e dentro do prazo no encerramento | CUMPRIDO | Decisão do usuário. Ex.: chamado resolvido remotamente, sem atendimento em campo. |
| Relógio sem evento e com o prazo vencido | Violado no congelamento e devolvido ao chamador | A rotina de 15 minutos pode não ter passado; sem isso, o resultado diria CUMPRIDO para um prazo estourado. |
| Violação em ciclo anterior | Resultado VIOLADO, com `violou_em_ciclo_anterior = true` | Decisão do usuário ([sla - 04]), coerente com a RN-69. |
| Congelar duas vezes | A segunda chamada não faz nada | A apuração nunca muda (RN-68); a UNIQUE `(chamado_id, tipo)` é a última barreira. |
| Acesso ao endpoint sem a entidade Chamado | Decidido pelos dados da apuração: `cliente_id` para o cliente, `tecnico_sub` para o técnico. Outro cliente ou técnico recebe 404 (RN-126) | Decisão do usuário. O módulo chamado ainda não existe; quando existir, a checagem passa a ser feita pelo chamado. |
| Sem apuração | Gestor: 200 `{"apurado": false}`. Cliente e técnico: 404 | Sem a entidade Chamado não há como saber de quem é um chamado sem apuração; responder `apurado: false` a qualquer um revelaria ids. |
| `UsuarioCorrente` no chamado | Importado direto | O chamado depende do identidade, e o identidade não depende do chamado: não há ciclo (`ModularidadeTest`). |
| `cliente_id` com FK | Sim, para `cliente` | A tabela existe; protege o relatório por cliente. |

## Atenção ao atualizar um ambiente existente

- A `V11` roda sozinha. Nenhuma variável nova.
- Quando a tabela `chamado` for criada, acrescentar a FK `apuracao_sla.chamado_id → chamado` e passar a checagem de acesso do endpoint para o chamado.
- O chamado deve chamar `congelarApuracao` na transação do encerramento (inclusive na validação automática), nunca no cancelamento (RN-19), e gravar na outbox as violações devolvidas.

## Como verificar

Ver `docs/testes/sla/sla-05-congelar-apuracao-consultar-resultado.md`.
