# Testes — [sla - 05] Congelar a apuração e consultar o resultado

> Executados em 14/11/2026, na branch `feat/SLA` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 273 testes, 0 falhas (ver [sla - 03]) | ✅ |
| 2 | Fotografia completa | `ApuracaoSlaTest.shouldFreezeEveryClockWithCallerData` | por relógio, prazo, vencimento, cumprimento, violação e resultado (CUMPRIDO, VIOLADO, CUMPRIDO); cliente, prioridade, técnico e `congeladaEm` do `Clock` | passou | ✅ |
| 3 | Relógio sem evento | `shouldFreezeClockWithoutEventAsFulfilledInsideDeadlineAndViolatedAfterIt` | dentro do prazo: CUMPRIDO; vencido: VIOLADO e a violação devolvida | passou | ✅ |
| 4 | Ciclo anterior | `shouldCountViolationOfPreviousCycle` (CAMPO violado no ciclo 1 e cumprido no prazo no ciclo 2) | CAMPO VIOLADO, `violouEmCicloAnterior = true`, `violadoEm` nulo (do ciclo 2) | passou | ✅ |
| 5 | Nunca muda | `shouldNeverChangeFrozenAssessment` | congelar de novo não altera as linhas; `UPDATE`, `DELETE` e `TRUNCATE` rejeitados pelo trigger | passou | ✅ |
| 6 | Política alterada depois | `shouldKeepAssessmentWhenPolicyChangesLater` (versão 2 criada depois) | linhas iguais; prazo continua o da versão 1 | passou | ✅ |
| 7 | Chamado sem relógios | `shouldRefuseToFreezeChamadoWithoutClocks` | `IllegalStateException` | passou | ✅ |
| 8 | Endpoint: quem tem acesso | `shouldShowAssessmentToManagerOwnerClientAndResponsibleTechnician` | 200 com `apurado: true` e os relógios para gestor, cliente da empresa e técnico responsável | passou | ✅ |
| 9 | Endpoint: isolamento (RN-126) | `shouldHideAssessmentFromOtherClientsAndTechnicians` | 404 `RECURSO_NAO_ENCONTRADO` para cliente de outra empresa e outro técnico | passou | ✅ |
| 10 | Endpoint: sem apuração (RN-19) | `shouldAnswerWithoutAssessmentForCancelledOrOpenChamado` | gestor: 200 `apurado: false`, sem `relogios`; cliente: 404 | passou | ✅ |
| 11 | Endpoint: token sem papel conhecido | `AcessoPorRotaTest.shouldDenySlaResultToTokenWithoutKnownRole` | 403 | passou | ✅ |
| 12 | Quebra: ciclo anterior ignorado no resultado | cópia, `-Dtest=ApuracaoSlaTest` | falha | teste 4 falhou: o CHECK `apuracao_sla_resultado_ck` barrou a gravação | ✅ |
| 13 | Quebra: congelar sem violar o vencido | idem | falha | teste 3 falhou | ✅ |
| 14 | Quebra: congelar duas vezes regrava | idem | falha | teste 5 falhou: a UNIQUE `apuracao_sla_relogio_uk` barrou | ✅ |
| 15 | Quebra: endpoint sem o filtro de empresa | idem | falha | teste 9: esperado 404, veio 200 | ✅ |
| 16 | Quebra: migração sem os triggers | idem | falha | teste 5 falhou | ✅ |
| 17 | Dev: migrações V10 e V11 | stack de dev completa a partir da cópia (`-p flowsla-verificacao`, volumes próprios); `flyway_schema_history`, `pg_indexes`, `pg_trigger` e `psql` | V10 e V11 com sucesso; índices e triggers criados; `UPDATE` na apuração rejeitado; `GET /api/chamados/{id}/sla` sem token → 401 | todos conforme o esperado | ✅ |

## Observações

1. **Achado:** nas quebras dos itens 12 e 14, o banco barrou o dado errado sozinho (CHECK e UNIQUE). As constraints são a última barreira; o código certo responde antes, sem erro.
2. **Observação de ambiente:** na cópia, `infra/rabbitmq` foi reetiquetada com `chcon -t container_file_t` (mesmo achado da outbox-03). O backend da cópia demorou alguns minutos para subir porque baixou as dependências do Maven dentro do container. O ambiente foi derrubado com `down -v` só no projeto `flowsla-verificacao`; os volumes `infra_*` não foram tocados.
3. **Não verificado:** o endpoint no dev com token real. O realm não permite login direto, e não há chamado encerrado para consultar, porque o módulo chamado não existe.
4. **Pendente para o módulo chamado:** acrescentar a FK de `chamado_id` em `relogio_sla` e `apuracao_sla` e passar a checagem de acesso do endpoint para a entidade Chamado.
