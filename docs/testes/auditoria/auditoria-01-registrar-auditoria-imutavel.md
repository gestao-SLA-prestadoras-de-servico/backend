# Testes — [auditoria - 01] Registrar auditoria imutável

> Executados em 29/10/2026, na branch `feat/auditoria` (criada a partir de `feat/autenticação`; alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers (`pgvector/pgvector:pg17`). Privilégios conferidos num `postgres:17` descartável com o `infra/postgres/init.sql` e no banco de dev.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 67 testes, 0 falhas (`AuditoriaTest` 8, `OperacaoAuditadaTest` 1 e os 58 anteriores) | ✅ |
| 2 | Fora de transação | `AuditoriaTest.shouldFailOutsideTransaction` | `IllegalTransactionStateException` | passou | ✅ |
| 3 | Quem, o quê, quando, qual entidade | `shouldStoreWhoWhatWhenAndWhichEntityWithinCallerTransaction` (`RelogioMutavel` em 2026-10-29T15:00Z) | `ocorrido_em` = hora do Clock; ator, perfil, operação, entidade, chamado e `dados` jsonb gravados | passou | ✅ |
| 4 | Ator sistema | `shouldStoreSystemActorWithoutSubOrProfile` | `ator_tipo SISTEMA`, `ator_sub` e `perfil` nulos, `dados` = `{}` | passou | ✅ |
| 5 | Rollback da operação | `shouldLeaveNoRecordWhenBusinessOperationRollsBack` | nenhum registro | passou | ✅ |
| 6 | UPDATE via SQL | `shouldRejectUpdateThroughSql` | erro "não pode ser alterado" | passou | ✅ |
| 7 | DELETE < 5 anos / > 5 anos | `shouldRejectDeleteWithinFiveYearsAndAllowAfter` (4 anos e 11 meses / 5 anos e 1 dia) | o primeiro falha ("período de retenção"); o segundo apaga 1 linha | passou | ✅ |
| 8 | TRUNCATE | `shouldRejectTruncate` | erro "não pode ser esvaziada" | passou | ✅ |
| 9 | CHECK do ator no banco | `shouldRejectUserActorWithoutSubInDatabase` | violação de `auditoria_ator_ck` | passou | ✅ |
| 10 | Enum cobre a RN-121 | `OperacaoAuditadaTest.shouldCoverEveryOperationOfRn121` | os 15 itens presentes | passou | ✅ |
| 11 | REVOKE com usuário sem superusuário | `postgres:17` descartável + `init.sql`; V1 a V4 aplicadas como `app_principal`; `has_table_privilege`; `UPDATE`, `TRUNCATE` e `DELETE` como `app_principal` | `UPDATE`/`TRUNCATE` sem privilégio; `INSERT`/`DELETE` com; DELETE recente barrado pelo trigger | `superuser=false`; `UPDATE=false TRUNCATE=false INSERT=true DELETE=true`; `permission denied` no UPDATE e no TRUNCATE; DELETE recente: "ainda está no período de retenção" | ✅ |
| 12 | Banco de dev | Backend de dev reiniciado pelo devtools com os arquivos finais; consulta de leitura em `pg_trigger` e `has_table_privilege` | V4 aplicada; triggers presentes; sem UPDATE/TRUNCATE | `now at version v4`, health 200; `auditoria_imutavel_linha` e `auditoria_imutavel_truncate`; `UPDATE=false TRUNCATE=false INSERT=true` | ✅ |

## Observações

1. **Por que o item 11 existe:** no Testcontainers o usuário do banco é superusuário, que ignora privilégios, então a suíte só prova o trigger, não o `REVOKE`. A conferência com `app_principal` foi feita à parte.
2. **Achado:** `TRUNCATE` não dispara trigger de linha. Sem o trigger de comando `auditoria_imutavel_truncate` (e o `REVOKE TRUNCATE`), um `TRUNCATE auditoria` apagaria a trilha inteira apesar da regra dos 5 anos.
3. **Limite conhecido:** o `INSERT` aceita qualquer `ocorrido_em`. Um registro gravado com data retroativa de mais de 5 anos poderia ser apagado em seguida. O serviço sempre usa o `Clock`; quem grava direto por SQL fura essa garantia. Não foi bloqueado no banco porque os testes com `RelogioMutavel` gravam horas diferentes da do servidor.
4. **Limite conhecido:** o dono da tabela (`app_principal`) pode desfazer a proteção com comando deliberado (ver Decisões na task).
5. **Ordem de merge:** esta branch parte de `feat/autenticação` (a migração é a V4 e o `Papel` movido vem de lá). O PR dela só deve entrar depois do PR de autenticação.
