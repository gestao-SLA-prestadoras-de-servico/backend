# [sla - 02] Criar os relógios: cálculo inicial e cumprimento

> Requisitos: RF-30, RF-33, RN-23 a RN-28, RN-64, RN-65, RN-67, RN-69 · Depende de: [cadastro - 03], [sla - 01]

## Contexto

Cada chamado tem três relógios independentes: resposta, atendimento em campo e resolução. Os prazos são calculados e gravados na abertura, não a cada consulta. Quando o evento correspondente acontece, o relógio é cumprido. Se o cumprimento ocorre depois do prazo e antes de a rotina de 15 minutos rodar, a violação precisa ser marcada ali mesmo; senão o aviso de violação nunca sairia.

O módulo sla não conhece o módulo chamado: ele devolve ao chamador que houve violação, e quem grava o evento na outbox é o chamado. Isso evita dependência circular entre os módulos.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| A abertura cria os três relógios com `vence_em` calculado pela versão da política e pela prioridade | `RelogiosSla.iniciarRelogios(chamadoId, versao, prioridade, instante)` grava RESPOSTA, CAMPO e RESOLUCAO do ciclo 1, com `prazo_minutos` tirado de `versao.prazos(prioridade)` e `vence_em = CalendarioJanela.adicionarMinutos(instante, prazo)`. |
| Cumprir um relógio grava `cumprido_em` só na primeira vez do ciclo | `RelogioSla.cumprir` não sobrescreve `cumprido_em` e devolve `cumpriuAgora = false` na repetição. |
| Cumprimento depois do prazo marca `violado_em = vence_em` e devolve "violou" ao chamador | `cumprir` chama `marcarViolacao()` quando o instante passa de `vence_em`, e devolve a `Violacao` em `ResultadoCumprimento`. O banco reforça com o CHECK `violado_em = vence_em`. |
| Relógio cumprido depois de violado continua VIOLADO | `estado()` dá prioridade à violação (RN-69). Uma violação já marcada pela rotina não é devolvida de novo: o aviso não se repete. |
| Os três relógios violam de forma independente | Cada relógio é uma linha com o seu vencimento; `RelogiosSlaTest` viola a resposta, cumpre o campo no prazo e deixa a resolução correndo. |
| O módulo sla não importa nada do módulo chamado (teste de modularidade passa) | `ModularidadeTest` passa e o novo `SlaArquiteturaTest` proíbe explicitamente qualquer dependência do sla para o chamado. |
| Testes unitários criados e passando | `RelogioSlaTest` (unitário, 7) e `RelogiosSlaTest` (integração, 6). |

## Passos técnicos realizados

- [x] **Migração da tabela `relogio_sla`** (`V10__cria_relogio_sla.sql`): `chamado_id`, `tipo`, `ciclo`, `ativo`, `marco_inicial`, `prazo_minutos`, `vence_em`, `cumprido_em`, `violado_em` e `version`. Restrições:
  - UNIQUE `(chamado_id, tipo, ciclo)`;
  - índice único parcial `(chamado_id, tipo) WHERE ativo`: um relógio ativo por tipo;
  - CHECK `violado_em IS NULL OR violado_em = vence_em`;
  - índice parcial em `vence_em` dos relógios correndo, para a rotina de 15 minutos.
- [x] **`iniciarRelogios(chamadoId, versaoPolitica, prioridade, instante)`**.
- [x] **`cumprir(chamadoId, tipo, instante)` retornando `ResultadoCumprimento`**: `cumpriuAgora` e a `Violacao` opcional.
- [x] **`marcarViolacao(relogio)`**: método da entidade `RelogioSla`, único ponto que grava `violado_em`. Devolve a violação só na primeira vez.
- [x] **Estado derivado CORRENDO, CUMPRIDO, VIOLADO**: `EstadoRelogio`, calculado em `RelogioSla.estado()` e exposto em `RelogioView`; não é gravado.
- [x] Consulta `relogiosAtivos(chamadoId)` e, no cadastro, `ContratoConsulta.versaoDaPolitica(versaoId)`, para o chamado reler a versão guardada na abertura.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Nome do tipo do segundo relógio | `CAMPO` | Decisão do usuário. Na notificação, o chamado converte para `ATENDIMENTO_CAMPO` (contrato publicado na outbox-04). |
| Versão da política | O sla recebe `PoliticaDoContrato` do chamador; o cadastro ganhou `versaoDaPolitica(versaoId)` | Decisão do usuário. O sla não busca nada sozinho; o chamado relê a versão que guardou (RN-05). |
| Cumprimento exatamente no vencimento | No prazo | A RN-65 fala em prazo "ultrapassado". |
| Transação | Escritas com `propagation = MANDATORY` | Rodam na transação do chamado, como a outbox e a auditoria: a violação devolvida e o aviso na outbox commitam juntos. |
| `chamado_id` sem FK | Provisório; a FK entra com a tabela `chamado` | A tabela ainda não existe. Registrado em `convencoes-banco.md`. |
| `marco_inicial` | O instante informado (abertura), sem normalizar | É o marco do evento e compõe o `eventoId` da violação (RN-54); a normalização só afeta o `vence_em`. |
| `Violacao.violadaEm` | Igual ao `vence_em` | RN-67: horário real do estouro, não o da detecção. |
| Cumprir sem relógio ativo | `IllegalStateException` | É erro de programação do chamador, não regra de negócio para o usuário. |
| Concorrência | `@Version` no relógio | Dois cumprimentos simultâneos: o segundo falha com conflito em vez de sobrescrever. |
| Regra explícita sla ↛ chamado | `SlaArquiteturaTest` (ArchUnit) | O `ModularidadeTest` só barra ciclos; o sla importar `chamado.api` passaria nele (verificado). |

## Atenção ao atualizar um ambiente existente

- A `V10` roda sozinha. Nenhuma variável nova.
- Quando a tabela `chamado` for criada, acrescentar a FK `relogio_sla.chamado_id → chamado`.
- O chamado deve chamar `iniciarRelogios` na abertura e `cumprir` no primeiro posicionamento (RESPOSTA), no primeiro atendimento presencial (CAMPO) e ao passar a RESOLVIDO (RESOLUCAO), na mesma transação, e gravar na outbox cada `Violacao` devolvida.

## Como verificar

Ver `docs/testes/sla/sla-02-relogios-calculo-cumprimento.md`.
