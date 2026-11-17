# [sla - 01] Implementar o calendário da janela comercial

> Requisitos: RN-29, RN-60, RN-61, RN-62, RN-63 · Depende de: [plataforma - 04]

## Contexto

Os prazos de SLA só correm dentro da janela de atendimento: segunda a sexta, das 08h às 17h. Todo cálculo de prazo do sistema passa por esta função. Ela precisa ser pura (sem banco) e testada à exaustão, porque um erro aqui contamina todos os prazos.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Sexta 16:30 + 120 min → segunda 09:30 | `CalendarioJanela.adicionarMinutos` consome os 30 min que restam na sexta e os 90 restantes a partir da abertura de segunda. Caso parametrizado em `CalendarioJanelaTest`. |
| Sábado 10:00 + 540 min → segunda 17:00 | Início fora da janela é normalizado para a próxima abertura (segunda 08:00). Um prazo que se completa exatamente no fechamento vence às 17:00 do mesmo dia. |
| Terça 08:00 + 2700 min → segunda seguinte 17:00 | Cinco dias úteis de 540 min, pulando o fim de semana. |
| Quarta 07:59 + 60 min e quarta 08:00 + 60 min → ambos quarta 09:00 | Antes das 08:00, conta a partir da abertura do dia. |
| `minutosUteisEntre` é o inverso de `adicionarMinutos` (teste por propriedade) | `CalendarioJanelaPropriedadesTest` (jqwik): para 3000 instantes e prazos aleatórios, com segundos quebrados, `minutosUteisEntre(t, adicionarMinutos(t, n)) == n`. Junto, duas propriedades: o prazo sempre cai dentro da janela, e somar em duas etapas dá o mesmo que somar de uma vez. |
| Feriados são tratados como dia útil | Não há calendário de feriados: só sábado e domingo ficam fora. Testado com 25/12 e 01/01. |

## Passos técnicos realizados

- [x] **`CalendarioJanela` no módulo sla, recebendo o `Clock`**: `sla.api.CalendarioJanela`, `@Component`. Do `Clock` usa só o fuso (`clock.getZone()`, que vem de `FUSO_HORARIO`); não lê a hora atual.
- [x] **`adicionarMinutos(instante, minutos)`**: anda dia a dia, consumindo o que resta até as 17:00. Minutos negativos lançam `IllegalArgumentException`.
- [x] **`minutosUteisEntre(inicio, fim)`**: soma, para cada dia útil, a interseção do intervalo com 08:00–17:00; arredonda para baixo. Devolve 0 quando `fim` não é depois de `inicio`.
- [x] **Normalização de início fora da janela**: antes das 08:00 vai para a abertura do dia; a partir das 17:00 ou no fim de semana, para a próxima abertura.
- [x] **Testes unitários parametrizados e teste por propriedade (jqwik)**: `CalendarioJanelaTest` (24 casos) e `CalendarioJanelaPropriedadesTest` (3 propriedades × 3000 tentativas).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Prazo que cai exatamente às 17:00 (decisão pendente da task) | Vence às 17:00 do mesmo dia | Sugestão da task, e o próprio critério "sábado 10:00 + 540 → segunda 17:00" exige isso. |
| Início às 17:00 em ponto | Já está fora: conta a partir da próxima abertura | A janela é 08:00 inclusive até 17:00 exclusive para começar a contar. |
| Lugar da classe | `sla.api` | O chamado vai precisar dela (ex.: os 3 dias úteis da validação automática). |
| Precisão | Instante completo (segundos e nanossegundos); `minutosUteisEntre` arredonda para baixo | A abertura do chamado não cai em minuto redondo; arredondar antes quebraria o inverso. |
| Biblioteca de propriedades | jqwik 1.9.3, só em escopo de teste | Pedida pela task. Roda com o JUnit 6.0.3 do projeto (verificado: as 3000 tentativas aparecem no relatório). |
| Base de amostras do jqwik | `target/.jqwik-database`, via `src/test/resources/junit-platform.properties` | Por padrão o jqwik grava `.jqwik-database` na raiz do projeto, que entraria no git. |
| Custo | Um passo por dia em `minutosUteisEntre` | Prazos de SLA são de dias úteis; não há intervalo de anos. |

## Atenção ao atualizar um ambiente existente

- Sem migração nem variável nova. Dependência nova só de teste: `net.jqwik:jqwik:1.9.3`.
- Todo cálculo de prazo do sistema deve passar por `CalendarioJanela`; o README registra a regra.

## Como verificar

Ver `docs/testes/sla/sla-01-calendario-janela-comercial.md`.
