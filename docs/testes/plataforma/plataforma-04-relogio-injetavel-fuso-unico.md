# Testes — [plataforma - 04] Criar o relógio injetável e o fuso único

> Executados em 22/10/2026, na branch `feat/plataforma` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). A máquina já está em `America/Fortaleza`, então a suíte também foi rodada com `TZ=UTC` para garantir que o fuso vem da configuração.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `./mvnw test` | tudo passa | `ModularidadeTest` 2, `RelogioArquiteturaTest` 1, `BackendApplicationTests` 5; 0 falhas | ✅ |
| 2 | Suíte completa com a JVM em outro fuso | `TZ=UTC ./mvnw clean test` | tudo passa | mesmos 8 testes, 0 falhas | ✅ |
| 3 | Clock, JVM, sessão JDBC e Hibernate no mesmo fuso | `shouldUseSameTimeZoneInClockJvmJdbcAndHibernate` | `America/Fortaleza` nos quatro | passou | ✅ |
| 4 | Avançar o relógio e disparar a rotina | `shouldAdvanceClockAndRunRoutineWithoutWaiting` | prazo de 4h vence após `avancar(4h01)`, sem espera | passou | ✅ |
| 5 | Regra ArchUnit pega violações | Classe temporária em `sla.internal` com `Instant.now()`, `LocalDateTime.now(ZoneId)`, `Clock.systemUTC()`, `System.currentTimeMillis()` e `Instant.now(clock)` | 4 violações; `now(clock)` aceito | `was violated (4 times)`, as quatro chamadas proibidas listadas | ✅ |
| 6 | Teste 3 pega fuso errado | `TZ=UTC`, sem `TimeZone.setDefault` e sem `connection-init-sql` | teste 3 falha | `expected: America/Fortaleza but was: UTC` | ✅ |
| 7 | `Instant` correto com `hibernate.jdbc.time_zone` | Teste temporário: query nativa com parâmetro `Instant` `2026-01-05T12:00:00Z` | texto `2026-01-05 09:00:00-03` e leitura de volta igual ao original | `txt=2026-01-05 09:00:00-03 back=2026-01-05T12:00:00Z` | ✅ |
| 8 | Sessão JDBC sem o `connection-init-sql` | Removido o `connection-init-sql`, máquina em `America/Fortaleza` | — (investigação) | passou: o pgjdbc envia o fuso da JVM, já definido pelo `ClockConfig` | ℹ️ |

Classe, teste e alterações temporárias dos itens 5 a 8 foram feitos numa cópia do projeto e descartados.

## Observações

1. **Item 8:** o `connection-init-sql` foi mantido mesmo sem ser necessário nesse cenário, porque sem ele o fuso da sessão depende de o `ClockConfig` rodar antes do pool abrir a primeira conexão.
2. **Ambiente:** o `target/maven-status` do repositório pertence ao root (criado pelo container dev) e o `./mvnw` local falha ao gravar nele. Os testes rodaram numa cópia do projeto. Para rodar no repositório: `sudo chown -R $USER target` ou `sudo rm -rf target`.
3. **Não verificado:** subir o ambiente via `make run-dev` e conferir `show timezone` no Postgres do compose (`TZ`/`PGTZ`).
