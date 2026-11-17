# Testes — [sla - 02] Criar os relógios: cálculo inicial e cumprimento

> Executados em 14/11/2026, na branch `feat/SLA` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 250 testes, 0 falhas (`RelogioSlaTest` 7, `RelogiosSlaTest` 6, `SlaArquiteturaTest` 1, 1 caso novo no `PoliticaContratoTest`, os 27 da [sla - 01] e os 208 anteriores) | ✅ |
| 2 | Estado inicial | `RelogioSlaTest.shouldStartRunning` | CORRENDO | passou | ✅ |
| 3 | Cumprimento no prazo | `shouldFulfillOnTimeWithoutViolation` | `cumpriuAgora`, sem violação, CUMPRIDO | passou | ✅ |
| 4 | Cumprimento exatamente no vencimento | `shouldNotViolateWhenFulfilledExactlyAtDeadline` | sem violação | passou | ✅ |
| 5 | Cumprimento atrasado | `shouldMarkViolationAtDeadlineWhenFulfilledLate` | `violado_em = vence_em`; `Violacao` com chamado, tipo, ciclo, marco e vencimento; VIOLADO | passou | ✅ |
| 6 | Só a primeira vez do ciclo | `shouldKeepFirstFulfillmentOfCycle` (unitário) e `RelogiosSlaTest.shouldKeepFirstFulfillmentWhenCalledAgain` (banco) | `cumpriuAgora = false`; `cumprido_em` do primeiro evento | passou | ✅ |
| 7 | Cumprido depois de violado (RN-69) | `shouldStayViolatedWhenFulfilledAfterViolation` | continua VIOLADO; a violação não é devolvida de novo | passou | ✅ |
| 8 | Violação uma vez só | `shouldMarkViolationOnlyOnce` | segunda chamada devolve vazio | passou | ✅ |
| 9 | Abertura (RF-30, RN-28) | `RelogiosSlaTest.shouldStartThreeClocksWithDeadlinesFromPolicyVersionAndPriority`: política `Padrão`, sexta 16:30, ALTA e NORMAL | ALTA: 120/540/1080 vencendo segunda 09:30, segunda 16:30, terça 16:30. NORMAL: 540/1620/2700 vencendo segunda 16:30, quarta 16:30, sexta 20/11 16:30. Ciclo 1, ativos, marco = abertura | passou | ✅ |
| 10 | Independência (RN-27) | `shouldViolateEachClockIndependently` | RESPOSTA violada às 09:30 (gravado no banco), CAMPO cumprido, RESOLUCAO correndo | passou | ✅ |
| 11 | Sem relógio ativo | `shouldRejectFulfillingClockThatDoesNotExist` | `IllegalStateException` | passou | ✅ |
| 12 | Transação do chamador | `shouldRequireCallerTransaction` | `IllegalTransactionStateException`; nada gravado | passou | ✅ |
| 13 | Um ativo por tipo | `shouldAllowOnlyOneActiveClockPerTypeAndCycle` | iniciar duas vezes e inserir outro CAMPO ativo violam `relogio_sla_ciclo_uk` e `relogio_sla_ativo_uk` | passou | ✅ |
| 14 | Versão por id no cadastro | `PoliticaContratoTest.shouldFindSpecificPolicyVersionById` | a versão 1 continua acessível depois da versão 2; versão inexistente → `RecursoNaoEncontradoException` | passou | ✅ |
| 15 | sla não depende do chamado | `SlaArquiteturaTest` e `ModularidadeTest` | passam | passaram | ✅ |
| 16 | Quebra: `violado_em` com a hora da detecção | cópia, `-Dtest=RelogioSlaTest,RelogiosSlaTest` | falha | teste 5 falhou; no banco, o CHECK `relogio_sla_violado_ck` barrou a gravação (teste 10) | ✅ |
| 17 | Quebra: `cumprido_em` regravado | idem | falha | testes 6 (unitário e integração) falharam | ✅ |
| 18 | Quebra: vencimento exato conta como violação | cópia, `-Dtest=RelogioSlaTest` | falha | teste 4 falhou | ✅ |
| 19 | Quebra: `RelogioSla` referenciando um tipo de `chamado.api` | cópia, `-Dtest=SlaArquiteturaTest,ModularidadeTest` | falha | `SlaArquiteturaTest` falhou; **o `ModularidadeTest` passou** (ver obs. 1) | ✅ |

## Observações

1. **Achado:** o `ModularidadeTest` (Spring Modulith) só barra ciclos e acesso a `internal`. Uma dependência do sla para o `chamado.api` passaria nele (item 19). Por isso o critério "o sla não importa nada do chamado" ganhou a regra `SlaArquiteturaTest`.
2. **Não verificado no dev:** a `V10` rodou no Postgres 17 do Testcontainers, a mesma imagem do dev. A verificação no stack de dev fica junto com a `V11`, na [sla - 05].
3. **Não verificado:** cumprimentos simultâneos do mesmo relógio. O `@Version` deve fazer o segundo falhar com conflito; não há teste concorrente.
