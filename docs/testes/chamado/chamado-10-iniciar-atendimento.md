# Testes — [chamado - 10] Iniciar atendimento e registrar a descrição técnica

> Executados em 16/11/2026, na branch `feat/chamados_6-10` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 410 testes, 0 falhas (ver [chamado - 08]) | ✅ |
| 2 | Início | `InicioAtendimentoTest.shouldStartAttendanceFulfillingFieldClock` | EM_ATENDIMENTO; CAMPO CUMPRIDO às 10:00; 1 atendimento EM_ANDAMENTO com `iniciadoEm`; `MUDANCA_ESTADO` com o técnico como ator | passou | ✅ |
| 3 | Usa o PENDENTE | `shouldUsePendingAttendance` | o PENDENTE existente vira EM_ANDAMENTO, sem criar outro | passou | ✅ |
| 4 | Início após reatribuição | `shouldNotChangeFieldClockOnStartAfterReassignment` | o novo técnico inicia; CAMPO continua com o `cumpridoEm` do primeiro; atendimentos INTERROMPIDO e EM_ANDAMENTO; nenhuma violação | passou | ✅ |
| 5 | Início atrasado | `shouldViolateFieldClockWhenStartIsLate` | CAMPO VIOLADO; aviso `VIOLACAO` (`ATENDIMENTO_CAMPO`) com o `eventoId` determinístico | passou | ✅ |
| 6 | Técnico não atribuído | `shouldAnswer404ToTechnicianNotAssigned` | 404 no início e na descrição; estado mantido | passou | ✅ |
| 7 | Só a partir de ATRIBUIDO | `shouldStartOnlyFromAssigned` (EM_ATENDIMENTO, RESOLVIDO, ENCERRADO, CANCELADO) | 422 `TRANSICAO_INVALIDA`; nenhum atendimento criado | passou | ✅ |
| 8 | Descrição | `shouldRegisterDescriptionOnlyWhileInProgress` | sem atendimento e com PENDENTE → 422; com EM_ANDAMENTO → 200 e reescrita; em branco → 400 | passou | ✅ |
| 9 | Papéis | `shouldAllowOnlyTechnician` e `AcessoPorRotaTest` | gestor e cliente → 403, antes de validar o corpo | passou | ✅ |
| 10 | Quebra: início sem `cumprir(CAMPO)` | cópia, `-Dtest=InicioAtendimentoTest` | falha | testes 2, 4 e 5 falharam | ✅ |
| 11 | Quebra: início ignora o PENDENTE | idem | falha | testes 3 e 8: 500 (o índice único parcial barrou o segundo atendimento corrente) | ✅ |
| 12 | Quebra: descrição fora de EM_ANDAMENTO | idem | falha | teste 8: 422 esperado, veio 200 | ✅ |
| 13 | Quebra: técnico não atribuído inicia | idem | falha | teste 6 falhou | ✅ |
| 14 | Dev: migração V14 | stack de dev completa a partir da cópia (`-p flowsla-verificacao`, volumes próprios); `flyway_schema_history` e `pg_constraint` no banco `app_principal` | V14 aplicada; PK, FK para `chamado` e `preferencia_notificacao_uk` | V14 com sucesso; os três constraints presentes; `Started BackendApplication` com `ddl-auto=validate` | ✅ |

## Observações

1. **Observação de ambiente:** o ambiente de verificação foi derrubado com `down -v` só no projeto `flowsla-verificacao`; os volumes `infra_*` de dev não foram tocados.
2. **Achado:** na quebra do item 11, o índice único parcial do atendimento (chamado-02) impediu o segundo atendimento corrente, e a resposta foi 500. O índice protege o dado; a resposta certa depende de o serviço usar o PENDENTE.
3. **Não verificado:** a reabertura real criando o PENDENTE. O teste 3 cria o PENDENTE pelo serviço, porque a reabertura é de uma task futura.
4. **Não verificado no dev:** os endpoints com token real. O roteiro está na seção 7F do `docs/plano-testes-manuais.md`.
