# [plataforma - 01] Organizar o projeto em módulos e adicionar as dependências base

> Requisito: seção 3.1 (monólito modular) · Depende de: nenhuma

## Contexto

O backend (Spring Boot 4.1.1, Java 21, pacote `com.gestaosla.backend`) era um esqueleto com `HelloWorldController`. Como o app-principal é um monólito modular, sem fronteiras verificadas automaticamente os módulos passariam a se acessar por dentro e o projeto viraria um monólito comum.

A solução cria os 10 módulos, adiciona as dependências usadas pelo restante do backlog e um teste que reprova o build quando um módulo acessa o pacote interno de outro ou quando surge ciclo entre módulos.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Existem os módulos `shared`, `identidade`, `cadastro`, `chamado`, `sla`, `evidencia`, `outbox`, `auditoria`, `relatorio` e `retencao` em `com.gestaosla.backend` | Um pacote por módulo, cada um com `api/` (pública, marcada com `@NamedInterface("api")`) e `internal/` (privada). O `ModularidadeTest` confere que os 10 módulos são detectados. |
| O build falha se um módulo importar pacote interno de outro ou se houver ciclo | `ModularidadeTest` chama `ApplicationModules.of(BackendApplication.class).verify()` (Spring Modulith). Só `<modulo>.api` é exposto; importar `internal` de outro módulo ou criar ciclo faz o teste falhar, e o job de testes unitários do CI falha junto. |
| `make unit-test` e `make integration-test` executam testes em subpacotes | Filtros do `Makefile` trocados de `unit/*` e `integration/*` para `unit/**` e `integration/**`. |
| Nenhum segredo em `application.properties`; tudo vem de `.envs/` | Banco e JPA continuam como placeholders `${...}` lidos de `.envs/`. Foi adicionada só configuração não sensível (`management.endpoints.web.exposure.include`). Credenciais de RabbitMQ e `issuer-uri` do OAuth2 entram nas tasks que criarem esses serviços, sempre via `.envs/`. |
| `HelloWorldController` e seu teste removidos ou substituídos | Controller, `HelloWorldControllerTest` e o teste de Hello World do teste de integração foram removidos, junto com a propriedade `app.environment`, que só eles usavam. `BackendApplicationTests` mantém a verificação de conexão com o banco, que também prova que o contexto sobe com as novas dependências. |
| O CI (`.github/workflows/ci.yml`) continua passando | Os três jobs (build, testes unitários e de integração) foram executados localmente com os mesmos comandos do CI. O workflow não precisou mudar. |
| Seção "Stack" do README atualizada | Tabela Stack atualizada e nova seção com o mapa de módulos e a regra de fronteira. |

## Passos técnicos realizados

- [x] **Criar um pacote por módulo com subpacote de API pública e subpacote interno**
  `com.gestaosla.backend.<modulo>.api` (`package-info.java` com `@NamedInterface("api")`) e `com.gestaosla.backend.<modulo>.internal` (`package-info.java` documentando que é privado). Outros módulos só podem depender de `<modulo>.api`.

- [x] **Adicionar ao `pom.xml` OAuth2 Resource Server, AMQP, Actuator, Flyway e micrometer-registry-prometheus (nomes conferidos no Boot 4)**
  Conferidos no BOM `spring-boot-dependencies:4.1.1`:
  - `spring-boot-starter-security-oauth2-resource-server`
  - `spring-boot-starter-amqp`
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` (desde o Flyway 10, o suporte a cada banco fica num módulo separado; o BOM traz o Flyway 12.4.0)
  - `io.micrometer:micrometer-registry-prometheus` (Micrometer 1.17.1)

  Todas as versões são gerenciadas pelo BOM do Boot.

- [x] **Adicionar Spring Modulith somente para verificação**
  BOM `spring-modulith-bom:2.1.1` (linha compatível com o Boot 4.1) importado em `dependencyManagement`. Entraram só `spring-modulith-api` (anotações, como `@NamedInterface`) e `spring-modulith-core` em escopo `test` (`ApplicationModules.verify()` via ArchUnit). **Não** foram adicionados `spring-modulith-starter-*` nem `spring-modulith-events-*`: o registro e a externalização de eventos ficam de fora porque o projeto tem outbox própria (módulo `outbox`).

- [x] **Criar `ModularidadeTest` em `com.gestaosla.backend.unit`**
  Dois testes: um confere que os 10 módulos são detectados e o outro executa `verify()` (acesso a pacote interno e ciclos). Não sobe contexto Spring nem Docker, por isso roda no job de testes unitários.

- [x] **Trocar no Makefile `unit/*` e `integration/*` por `unit/**` e `integration/**`**
  `./mvnw -Dtest="com/gestaosla/backend/unit/**" test` e `./mvnw -Dtest="com/gestaosla/backend/integration/**" test`.

- [x] **Conferir a compatibilidade das bibliotecas de resiliência com o Boot 4 e registrar a escolha**
  Escolha: **`@Retryable` / `@ConcurrencyLimit` nativos do Spring Framework 7** (`org.springframework.resilience`, ativados com `@EnableResilientMethods`).
  Motivos:
  - já vêm no Spring Framework 7.0.x do Boot 4, sem dependência nova;
  - o Resilience4j não é gerenciado pelo BOM do Boot 4 e seu módulo `resilience4j-spring-boot3` foi feito para o Boot 3.

  Nada foi ativado agora: a task que precisar de retry adiciona `@EnableResilientMethods`.

- [x] **Atualizar o README (stack e mapa de módulos)**
  Stack: OAuth2 Resource Server, Spring AMQP (RabbitMQ), Actuator + Micrometer Prometheus, Flyway, Spring Modulith (só verificação) e resiliência com Spring Framework 7. Mapa de módulos: responsabilidade de cada módulo e a regra de fronteira (`api` é público, `internal` é privado, ciclos reprovam o build).

## Como verificar

```bash
./mvnw --batch-mode package -DskipTests   # job Build do CI
make unit-test                            # ModularidadeTest (fronteiras e ciclos)
make integration-test                     # requer Docker (Testcontainers)
```

Teste negativo (manual, não commitado): criar uma classe pública em `chamado/internal` e importá-la em `sla/internal` deve fazer o `make unit-test` falhar. Uma dependência circular entre `sla.api` e `chamado.api` também deve fazer o teste falhar.
