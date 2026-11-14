# [chamado - 02] Criar o modelo de atendimento

> Requisitos: RN-21, RN-70, RN-71, RN-95 · Depende de: [chamado - 01]

## Contexto

Cada passagem do técnico pelo chamado é um atendimento, e toda evidência pertence a um atendimento. A reabertura cria um novo atendimento. A reatribuição e o cancelamento interrompem o atual sem apagar as fotos. O atendimento fica dentro do módulo chamado para não criar dependência circular.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Existe no máximo um atendimento PENDENTE ou EM_ANDAMENTO por chamado | Índice único parcial `atendimento_corrente_uk (chamado_id) WHERE estado IN ('PENDENTE','EM_ANDAMENTO')`. Vale também para duas transações simultâneas e para SQL direto. |
| Chamado reaberto duas vezes tem três atendimentos | Atendimento nunca é apagado. Cada `criarPendente` acrescenta uma linha, e os anteriores ficam com o seu estado final. A reabertura ainda não existe; o teste reproduz a sequência criar → interromper → criar → interromper → criar. |
| Interromper um atendimento preserva as evidências | `interromper` só muda o estado para INTERROMPIDO e grava `encerrado_em`. Não existe remoção de atendimento, então o que aponta para ele continua apontando. A tabela de evidências ainda não existe: o teste prova que a linha do atendimento fica. |
| Testes unitários criados e passando | `AtendimentoTest` (unitário, 5) e `AtendimentoChamadoTest` (integração, 5). |

## Passos técnicos realizados

- [x] **Migração da tabela `atendimento`** (`V13__cria_atendimento.sql`): `chamado_id` (FK), `tecnico_sub`, `estado`, `iniciado_em`, `encerrado_em`, `descricao_tecnica` e `version`, com o índice único parcial.
- [x] **Enum `EstadoAtendimento`** (PENDENTE, EM_ANDAMENTO, CONCLUIDO, INTERROMPIDO), em `chamado.api`, com `corrente()`.
- [x] **`criarPendente`, `interromperCorrente` e `correnteDe`** em `AtendimentoService`. A escrita usa `propagation = MANDATORY`, na transação da operação do chamado. `doChamado` lista todos os atendimentos, para a consulta da [chamado - 04].
- [x] **Consulta do atendimento corrente na API do módulo**: `chamado.api.AtendimentoConsulta.correnteDe(chamadoId)`, que devolve `Optional<AtendimentoCorrente>`. Será usada pelo módulo evidencia.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Garantia de "um corrente por chamado" | Índice único parcial no banco | Restrição nativa, que vale em corrida e sem código de checagem. O serviço não consulta antes de inserir. |
| Interromper sem corrente | Não faz nada | A reatribuição de um chamado ATRIBUIDO pode não ter atendimento começado; não é erro. |
| Interromper atendimento já encerrado | `IllegalStateException` | É erro de programação do chamador: `interromperCorrente` só pega o corrente. |
| Concorrência | `@Version` no atendimento | Conclusão pelo técnico e interrupção pelo gestor ao mesmo tempo: a segunda falha com conflito. |
| `iniciado_em` | Nulo até o técnico iniciar o atendimento presencial (RF-22, task futura) | O atendimento nasce PENDENTE na atribuição; a hora de criação está em `created_at`. |

## Atenção ao atualizar um ambiente existente

- A `V13` roda sozinha. Nenhuma variável nova.
- A atribuição, a reabertura, a reatribuição e o cancelamento (tasks futuras) devem chamar `criarPendente` e `interromperCorrente` na mesma transação da mudança de estado.

## Como verificar

Ver `docs/testes/chamado/chamado-02-modelo-atendimento.md`.
