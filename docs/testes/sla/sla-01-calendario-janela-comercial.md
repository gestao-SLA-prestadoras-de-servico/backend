# Testes — [sla - 01] Implementar o calendário da janela comercial

> Executados em 14/11/2026, na branch `feat/SLA`, criada a partir de `cadastro_2_3_4` (alterações ainda não commitadas). Testes unitários, sem banco. As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 250 testes, 0 falhas (`CalendarioJanelaTest` 24, `CalendarioJanelaPropriedadesTest` 3 e os 208 anteriores, mais os 15 da [sla - 02]) | ✅ |
| 2 | Critérios de aceite | `CalendarioJanelaTest.shouldAddWindowMinutes`: sexta 16:30 + 120, sábado 10:00 + 540, terça 08:00 + 2700, quarta 07:59 + 60 e quarta 08:00 + 60 | segunda 09:30; segunda 17:00; segunda seguinte 17:00; quarta 09:00 nos dois | passou | ✅ |
| 3 | Bordas | mesmo teste: domingo; sexta 17:00 em ponto; sexta 18:30; prazo terminando às 17:00 e 1 min depois; 0 minutos; virada de mês e de ano; Natal | conforme a janela; 17:00 fica no mesmo dia; feriado conta como útil | passou | ✅ |
| 4 | `minutosUteisEntre` | `shouldCountWindowMinutesBetween` (8 casos, inclusive fim de semana, noite, intervalo vazio e invertido) | 120, 540, 2700, 60 e quatro zeros | passou | ✅ |
| 5 | Fuso vem do `Clock` | `shouldUseTimeZoneFromClock`, com a JVM em UTC | Fortaleza: segunda 09:30; o mesmo instante num calendário em UTC: segunda 10:00 UTC | passou | ✅ |
| 6 | Minutos negativos | `shouldRejectNegativeMinutes` | `IllegalArgumentException` | passou | ✅ |
| 7 | Inverso (propriedade) | `CalendarioJanelaPropriedadesTest.minutesBetweenShouldInvertAddMinutes`, jqwik, 3000 tentativas, instantes de 2020 a 2035 com segundos quebrados e 1 a 30 000 minutos | `minutosUteisEntre(t, adicionarMinutos(t, n)) == n` | passou; relatório do jqwik com `tries = 3000`, `checks = 3000` | ✅ |
| 8 | Prazo dentro da janela (propriedade) | `deadlineShouldAlwaysFallInsideWindow`, 3000 tentativas | dia útil, depois das 08:00 e até 17:00 | passou | ✅ |
| 9 | Soma em duas etapas (propriedade) | `addingInTwoStepsShouldEqualAddingAtOnce`, 3000 tentativas | `add(add(t, a), b) == add(t, a + b)` | passou | ✅ |
| 10 | jqwik com JUnit 6.0.3 | contagem do surefire | os 3 testes de propriedade descobertos e executados | `Tests run: 3` em `CalendarioJanelaPropriedadesTest`; os 24 do JUnit continuam rodando | ✅ |
| 11 | Quebra: sábado e domingo como dias úteis | cópia, `-Dtest=CalendarioJanela*` | falha | 12 casos parametrizados e a propriedade do item 8 falharam | ✅ |
| 12 | Quebra: prazo que termina às 17:00 passa para 08:00 do dia seguinte (`<` no lugar de `<=`) | idem | falha | 4 casos parametrizados falharam; **as 3 propriedades passaram** (ver obs. 1) | ✅ |
| 13 | Quebra: `minutosUteisEntre` contando até 18:00 | idem | falha | 3 casos parametrizados e a propriedade do inverso falharam | ✅ |

## Observações

1. **Achado:** as propriedades não pegam a regra das 17:00 (item 12). Com a mutação, `adicionarMinutos` e `minutosUteisEntre` continuam coerentes entre si, e o prazo das 08:00 do dia seguinte também cai "dentro da janela". Quem prende a regra são os casos parametrizados, inclusive o critério "sábado 10:00 + 540 → segunda 17:00". As propriedades e os exemplos se complementam.
2. **Achado:** o jqwik grava por padrão o arquivo `.jqwik-database` na raiz do projeto. Ele foi movido para `target/` em `src/test/resources/junit-platform.properties`, para não entrar no git.
3. **Observação:** o jqwik 1.9.3 roda com o JUnit 6.0.3 do projeto: o motor foi descoberto pelo surefire e o relatório mostra as 3000 tentativas de cada propriedade.
4. **Não verificado:** horário de verão. O fuso do sistema (`America/Fortaleza`) não tem; com um fuso que tenha, o dia da mudança não foi testado.
