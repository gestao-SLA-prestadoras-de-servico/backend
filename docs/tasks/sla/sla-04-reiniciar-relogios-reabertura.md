# [sla - 04] Reiniciar relógios na reabertura

> Requisitos: RF-32, RN-54, RN-96, RN-97 · Depende de: [sla - 02]

## Contexto

Quando o cliente reabre o chamado, os relógios de atendimento em campo e de resolução recomeçam com a data e hora da reabertura. O relógio de resposta não muda, porque o primeiro posicionamento já foi feito. O ciclo anterior não é apagado: fica como histórico.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Os relógios CAMPO e RESOLUCAO atuais ficam inativos e novos são criados com ciclo + 1 e marco = reabertura | `reiniciarParaReabertura(chamadoId, versao, prioridade, instante)` chama `RelogioSla.encerrarCiclo` nos ativos de CAMPO e RESOLUCAO e cria os do ciclo seguinte, com `marco_inicial = instante` e o vencimento calculado pela janela. |
| O relógio RESPOSTA não é alterado | O serviço só toca CAMPO e RESOLUCAO; o teste confere que a RESPOSTA continua no ciclo 1, cumprida, com o mesmo vencimento. |
| Violação de campo no ciclo 1 e nova violação no ciclo 2 são eventos diferentes (marcos diferentes) | Cada `Violacao` leva o `ciclo` e o `marcoInicial` do seu relógio; o `eventoId` determinístico do aviso usa o marco (RN-54). |
| Testes unitários criados e passando | 2 casos novos em `RelogioSlaTest` e 4 em `RelogiosSlaTest`. |

## Passos técnicos realizados

- [x] **`reiniciarParaReabertura(chamadoId, versaoPolitica, prioridade, instante)`**, com `propagation = MANDATORY`. Devolve `List<Violacao>`.
- [x] **Consulta de histórico de ciclos por chamado**: `RelogiosSla.historico(chamadoId)`, todos os relógios ordenados por tipo e ciclo.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Violação de um ciclo anterior na apuração final (decisão pendente da task) | Conta: o resultado fica VIOLADO, com o indicador `violouEmCicloAnterior` | Decisão do usuário, coerente com a RN-69 e com a sugestão da task. Implementado na [sla - 05]. |
| Ciclo encerrado ainda correndo e já vencido | A violação é marcada ao encerrar o ciclo e devolvida ao chamador | Sem isso, uma violação que a rotina de 15 minutos ainda não marcou se perderia: o relógio fica inativo e a rotina só olha os ativos. |
| Ordem das gravações | `flush` da inativação antes de inserir o ciclo novo | O índice único parcial admite um só ativo por tipo, e o Hibernate faria os INSERTs antes dos UPDATEs. |
| Versão e prioridade | As do chamado, passadas pelo chamador | Mesma regra do recálculo (RN-05). |

## Atenção ao atualizar um ambiente existente

- Sem migração nem variável nova.
- O chamado deve chamar `reiniciarParaReabertura` na mesma transação da reabertura e gravar na outbox as violações devolvidas.

## Como verificar

Ver `docs/testes/sla/sla-04-reiniciar-relogios-reabertura.md`.
