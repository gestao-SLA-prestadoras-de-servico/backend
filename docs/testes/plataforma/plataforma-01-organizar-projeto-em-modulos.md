# Testes — [plataforma - 01] Organizar o projeto em módulos e adicionar as dependências base

> Executados em 12/10/2026, na branch `develop` (alterações ainda não commitadas), em Fedora com SELinux `Enforcing` e Docker.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Testes unitários | `make unit-test` | `ModularidadeTest` passa (2 testes) | `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS | ✅ |
| 2 | Testes de integração (Testcontainers) | `make integration-test` | Contexto sobe com as novas dependências e conecta no Postgres | `Tests run: 1, Failures: 0, Errors: 0` — BUILD SUCCESS; Flyway executou (`No migrations found`) | ✅ |
| 3 | Build (mesmo comando do job Build do CI) | `./mvnw --batch-mode package -DskipTests` | Compila e empacota sem erro | Exit 0 | ✅ |
| 4 | Detecção dos 10 módulos | `ModularidadeTest.shouldDetectAllModules` | `shared`, `identidade`, `cadastro`, `chamado`, `sla`, `evidencia`, `outbox`, `auditoria`, `relatorio`, `retencao` detectados | Os 10 módulos detectados | ✅ |
| 5 | Acesso ao pacote interno de outro módulo | Classe em `sla.internal` importando `chamado.internal.X` (temporária), depois `make unit-test` | Build falha | Falhou: `Module 'sla' depends on non-exposed type com.gestaosla.backend.chamado.internal.X within module 'chamado'!` | ✅ |
| 6 | Dependência circular entre módulos | `chamado.api.A` → `sla.api.B` → `chamado.api.A` (temporárias), depois `make unit-test` | Build falha | Falhou: `Cycle detected: Slice chamado -> Slice sla` | ✅ |
| 7 | Makefile executa testes em subpacotes | `./mvnw clean`, teste temporário `unit/sub/SubTest`, depois `make unit-test` | `SubTest` é executado junto com o `ModularidadeTest` | `Running com.gestaosla.backend.unit.sub.SubTest` e `Running ...unit.ModularidadeTest` — `Tests run: 3, Failures: 0, Errors: 0`, BUILD SUCCESS | ✅ |
| 8 | Aplicação sobe no Docker (dev) | `docker compose ... stop`, depois `make build-dev` | `Started BackendApplication` | `Started BackendApplication in 3.655 seconds`; `Exposing 2 endpoints beneath base path '/actuator'` | ✅ |
| 9 | Endpoint de health exposto | `curl -i localhost:8080/actuator/health` | Responde; `DOWN` enquanto não houver RabbitMQ no compose | `503` — `{"status":"DOWN"}`; log: `Rabbit health check failed ... Connection refused` | ✅ |
| 10 | Endpoint Prometheus exposto e protegido | `curl -i localhost:8080/actuator/prometheus` | `401` sem credenciais | `401` com `WWW-Authenticate: Basic` | ✅ |
| 11 | Hello World removido | `curl -i localhost:8080/hello-world` | Rota não existe mais | `401` (Spring Security bloqueia antes do roteamento); controller e teste removidos do código | ✅ |
| 12 | Nenhum segredo em `application.properties` | `grep -nE 'password\|secret' src/main/resources/*.properties` | Só placeholders `${...}` vindos de `.envs/` | `spring.datasource.password=${DATABASE_PASSWORD}` | ✅ |
| 13 | Nenhum arquivo temporário restante | `git status --short` | Nenhum `X.java`, `Y.java`, `A.java`, `B.java` ou `SubTest.java` | Nenhum restante | ✅ |

## Observações

1. **Não verificado:** o conteúdo das métricas em `/actuator/prometheus`, que exige autenticação (fica para a task de autenticação/`identidade`).
