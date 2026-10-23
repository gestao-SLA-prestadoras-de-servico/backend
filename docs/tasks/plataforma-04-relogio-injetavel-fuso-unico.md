# [plataforma - 04] Criar o relógio injetável e o fuso único

> Requisitos: RN-62 (fuso horário único) · Depende de: [plataforma - 01]

## Contexto

Todo o cálculo de SLA depende de data e hora. Se cada classe chamar `now()` por conta própria, não dá para testar as rotinas de prazo sem esperar o tempo passar. Esta task cria um único `Clock`, com o fuso `America/Fortaleza` vindo de configuração, e impede (via ArchUnit) que o código de produção leia a hora do sistema por fora dele.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Existe um bean `Clock` com o fuso vindo de configuração | `shared.internal.ClockConfig` cria `Clock.system(fuso)` a partir de `app.fuso-horario`, que lê a variável `FUSO_HORARIO` dos `.envs`. Os outros módulos injetam `java.time.Clock` (tipo do JDK), então não importam nada de `shared.internal`. |
| JVM, conexão JDBC e banco usam o mesmo fuso | **JVM:** `ClockConfig` chama `TimeZone.setDefault(fuso)`. **JDBC/sessão do banco:** `spring.datasource.hikari.connection-init-sql=SET TIME ZONE '<fuso>'` em toda conexão do pool. **Hibernate:** `spring.jpa.properties.hibernate.jdbc.time_zone`. **Servidor Postgres:** `TZ`/`PGTZ` no `docker-compose.yml`. Todos leem o mesmo `FUSO_HORARIO`. Verificado por `shouldUseSameTimeZoneInClockJvmJdbcAndHibernate`. |
| Nenhum código de produção chama `now()` sem o `Clock` | `RelogioArquiteturaTest` (ArchUnit) reprova `now()`/`now(ZoneId)` de qualquer tipo do `java.time`, `Clock.system*()` e `System.currentTimeMillis()` fora do `ClockConfig`. `now(clock)` é permitido. |
| Um teste consegue avançar o relógio e disparar uma rotina sem esperar | `RelogioMutavel` (teste) substitui o `Clock` no contexto via `@Import(RelogioMutavel.Configuracao.class)`. `shouldAdvanceClockAndRunRoutineWithoutWaiting` avança 4h01 e a rotina de prazo vencido passa de `false` para `true` na hora. |

## Passos técnicos realizados

- [x] **Criar `ClockConfig` no módulo shared**
  `src/main/java/com/gestaosla/backend/shared/internal/ClockConfig.java`. Fica em `internal` porque ninguém referencia a classe: os módulos só injetam `Clock`.

- [x] **Configurar `spring.jpa.properties.hibernate.jdbc.time_zone`**
  Em `application.properties`, junto com `app.fuso-horario` e o `connection-init-sql`. `Instant` continua gravando o instante correto (verificado: `2026-01-05T12:00:00Z` vira `2026-01-05 09:00:00-03` e volta igual).

- [x] **Criar `RelogioMutavel` para testes**
  `src/test/java/com/gestaosla/backend/RelogioMutavel.java`: `Clock` com `definir(Instant)` e `avancar(Duration)`, começando em `2026-01-05T12:00:00Z` (determinístico). A classe interna `Configuracao` registra o bean como `@Primary` e está importada no `BackendApplicationTests`.

- [x] **Criar regra ArchUnit que proíbe `Instant.now()`, `LocalDateTime.now()` e similares sem Clock**
  Dependência `com.tngtech.archunit:archunit-junit5:1.4.1` (test) e `src/test/java/com/gestaosla/backend/unit/RelogioArquiteturaTest.java`. Roda em `make unit-test`.

## Como usar

```java
// produção: injetar o Clock
Instant agora = Instant.now(clock);

// teste de integração
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
...
relogio.definir(Instant.parse("2026-01-05T12:00:00Z")); // sempre no início: o contexto é reaproveitado
relogio.avancar(Duration.ofHours(4));
rotina.executar();                                       // chamar o método @Scheduled direto
```

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Origem do fuso | Variável `FUSO_HORARIO` nos `.envs` (perfil de teste fixa `app.fuso-horario`) | Mesmo padrão das demais configurações; o compose usa a mesma variável no Postgres. |
| Fuso da sessão JDBC | `connection-init-sql` explícito | O pgjdbc envia o fuso da JVM ao conectar, mas isso depende de o `ClockConfig` rodar antes do pool abrir a primeira conexão. O `SET TIME ZONE` não depende de ordem. |
| `hibernate.jdbc.time_zone` | Agora configurado (na plataforma-03 não era) | Pedido pela task; não altera `Instant` em `timestamptz` (verificado) e deixa `LocalDateTime`/`LocalDate` coerentes com o fuso, se surgirem. |
| `RelogioMutavel.withZone` | Lança `UnsupportedOperationException` | O sistema tem fuso único; uma cópia em outro fuso deixaria de avançar com o relógio do teste. |
| `created_at`/`updated_at` | Continuam com `@CreationTimestamp`/`@UpdateTimestamp` (hora real) | O Hibernate não aceita um `Clock` externo sem configuração interna de teste. São metadados de auditoria; prazos de SLA usam o `Clock`. Registrado em `docs/convencoes-banco.md`. |
| Proibidos pela regra | `now()` do `java.time`, `Clock.system*()`, `System.currentTimeMillis()` | `System.nanoTime()` fica liberado: mede duração, não lê data. `new Date()` não é usado no projeto. |

## Atenção ao atualizar um ambiente existente

- Adicione `FUSO_HORARIO=America/Fortaleza` ao seu `.envs/.env.*` local (o `.env.dev` já foi atualizado). Sem ela a aplicação não sobe.
- O `TZ` do Postgres só vale para volume novo (o `initdb` grava o fuso padrão). No dev, com volume `pgdata-dev` existente, o servidor continua em UTC, mas as conexões da aplicação usam o fuso certo pelo `connection-init-sql`. Para alinhar o servidor também: `make run-dev-down` (apaga o volume).

## Como verificar

Ver `docs/testes/plataforma-04-relogio-injetavel-fuso-unico.md`.
