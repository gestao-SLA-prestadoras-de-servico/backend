# FlowSLA - Backend

Bem-vindo ao repositório de backend do **FlowSLA** - um sistema para automatizar o controle de prazos, evidências e histórico de atendimento relacionados a SLAs (Service Level Agreements), voltado especialmente para empresas prestadoras de serviços técnicos de pequeno e médio porte.

## Qual é o propósito desse repositório?

Este repositório é responsável pelo backend do **FlowSLA**, incluindo as documentações relacionadas especificamente ao backend como mapeamento de endpoints, módulos, entre outros.

## Stack

| Ferramenta                                                                                                 | Versão                              |
| ---------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| [Java](https://docs.oracle.com/en/java/)                                                                   | 21                                  |
| [Spring Boot](https://spring.io/)                                                                          | 4.1.1                               |
| [Spring Data JPA](https://spring.io/projects/spring-data-jpa)                                              | 4.1.1                               |
| [Spring Security](https://spring.io/projects/spring-security)                                              | 7.x                                 |
| [Spring Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html) | 7.x                                 |
| [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)                        | 7.x                                 |
| [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html) | 7.x                    |
| [Spring AMQP (RabbitMQ)](https://spring.io/projects/spring-amqp)                                           | 4.x                                 |
| [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)                   | 4.1.1                               |
| [Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)   | 1.17.1                              |
| [Flyway](https://documentation.red-gate.com/flyway)                                                        | 12.4.0 (+ `flyway-database-postgresql`) |
| [Spring Modulith](https://spring.io/projects/spring-modulith)                                              | 2.1.1 (só verificação de módulos, nos testes) |
| [Spring Framework Resilience](https://docs.spring.io/spring-framework/reference/core/resilience.html)      | 7.x (`@Retryable` / `@ConcurrencyLimit`; escolhido no lugar do Resilience4j) |
| [PostgreSQL](https://www.postgresql.org/docs/17/)                                                          | 17 (imagem Docker)                  |
| [Maven](https://maven.apache.org/)                                                                         | -                                   |
| [Testcontainers](https://java.testcontainers.org/)                                                         | ^2.0.5 (PostgreSQL + JUnit Jupiter) |
| [ArchUnit](https://www.archunit.org/)                                                                      | 1.4.1 (regras de arquitetura, nos testes) |
| [Lombok](https://projectlombok.org/)                                                                       | -                                   |
| [Docker/DockerCompose](https://docs.docker.com/)                                                           | -                                   |
| [DockerCompose](https://docs.docker.com/compose/)                                                          | -                                   |

## Módulos

O backend é um monólito modular. Cada módulo fica em `com.gestaosla.backend.<modulo>`, com dois subpacotes:

- `api`: API pública do módulo. É o único pacote que outros módulos podem importar.
- `internal`: implementação privada do módulo.

| Módulo       | Responsabilidade                                       |
| ------------ | ------------------------------------------------------ |
| `shared`     | Tipos e utilitários comuns a todos os módulos          |
| `identidade` | Autenticação, usuários e permissões                    |
| `cadastro`   | Cadastros base (clientes, contratos, prestadores)      |
| `chamado`    | Abertura e ciclo de vida dos chamados                  |
| `sla`        | Regras de SLA, cálculo e controle de prazos            |
| `evidencia`  | Evidências anexadas aos atendimentos                   |
| `outbox`     | Outbox própria para publicação confiável de eventos    |
| `auditoria`  | Histórico e trilha de auditoria                        |
| `relatorio`  | Relatórios e indicadores                               |
| `retencao`   | Políticas de retenção e expurgo de dados               |

O `ModularidadeTest` (`make unit-test`) usa o Spring Modulith (`ApplicationModules.verify()`) e reprova o build se um módulo importar o pacote `internal` de outro ou se houver dependência circular entre módulos.

## Data e hora

O sistema tem fuso único (`FUSO_HORARIO`, hoje `America/Fortaleza`), usado pela JVM, pela sessão JDBC, pelo Hibernate e pelo Postgres. Quem precisa da hora injeta `java.time.Clock` (bean do `shared`); `Instant.now()`, `LocalDateTime.now()` e similares sem o Clock reprovam o `RelogioArquiteturaTest`. Nos testes, `RelogioMutavel` substitui o Clock e avança o tempo sem esperar.

## Erros da API

Todo erro sai como Problem Details (RFC 9457) com o campo `codigo`, tratado no `shared.internal.GlobalExceptionHandler`. Regra de negócio: `throw new RegraNegocioException(CodigoErro.X, "mensagem")` (422); recurso inexistente ou de outro cliente: `throw new RecursoNaoEncontradoException()` (404). Status, códigos e catálogo em `docs/erros-api.md`.

## Como executar o projeto:

> [!IMPORTANT]
>
> Requisitos:
>
> - Docker
> - Docker Compose 2.30 ou mais recente (usa `post_start`)

O projeto está dividido em três ambientes (desenvolvimento, testes e produção), abaixo estão as informações de como rodar cada um deles:

### Ambiente de Desenvolvimento

#### 1. Copie as variáveis de ambiente de desenvolvimento:

```bash
cp .envs/.env.dev.example .envs/.env.dev
```

#### 2. Rode o projeto via docker compose:

```bash
make run-dev
```

O ambiente sobe o backend e as dependências dele. O backend só inicia depois que todas estão saudáveis:

| Serviço    | Acesso em dev                                  | Observação                                                                 |
| ---------- | ---------------------------------------------- | -------------------------------------------------------------------------- |
| Backend    | http://localhost:8080                          | `/actuator/health`                                                         |
| PostgreSQL | só rede interna (`postgres:5432`)              | bancos `app_principal`, `notificacoes` e `keycloak`, um usuário por banco (`infra/postgres/init.sql`) |
| RabbitMQ   | http://localhost:15672 (management)            | topologia de `infra/rabbitmq/definitions.json`                             |
| MinIO      | http://localhost:9001 (console)                | a porta S3 (9000) não é publicada no host                                  |
| Keycloak   | http://localhost:8081                          | console admin em `KEYCLOAK_ADMIN_USER`/`KEYCLOAK_ADMIN_PASSWORD`; realm `flowsla` importado de `infra/keycloak/realm-flowsla.json` |
| Mailpit    | http://localhost:8025                          | caixa de e-mails de teste                                                  |

Em test e prod nenhuma porta de infraestrutura é publicada. Os serviços ficam na rede `flowsla-<ENVIRONMENT>`, que o ms-notificacoes usa como rede externa para acessar o PostgreSQL e o RabbitMQ compartilhados.

#### Credenciais de desenvolvimento (realm `flowsla`)

| O quê | Valor em dev | Uso |
| --- | --- | --- |
| Gestor inicial | usuário `gestor` (`gestor@flowsla.local`), senha `KEYCLOAK_GESTOR_PASSWORD` | primeiro login; cadastra os demais usuários. O `sub` é fixo e o mesmo da `V2__cria_usuario.sql` |
| Client do frontend | `flowsla-frontend` (público, Authorization Code + PKCE S256) | redirect em `FRONTEND_URL/*`; o token sai com `aud` `app-principal` e `ms-notificacoes` e com `realm_access.roles` |
| Conta de serviço | client `app-principal-admin`, segredo `KEYCLOAK_ADMIN_CLIENT_SECRET` | Admin API; só `manage-users`, `view-users` e `view-realm` |
| E-mails do Keycloak | SMTP `SMTP_HOST:SMTP_PORT` (Mailpit) | ver em http://localhost:8025 |

O backend valida o token em `KEYCLOAK_ISSUER_URI` (o `iss`) e busca as chaves em `KEYCLOAK_JWK_SET_URI`. `GET /api/usuarios/me` devolve o usuário do token.

> [!WARNING]
> O realm só é importado se ainda não existir no banco do Keycloak. Depois de alterar o `realm-flowsla.json`, rode `make run-dev-down` (apaga os dados de dev) e suba de novo.

> [!WARNING]
> O `init.sql` só roda com o volume do PostgreSQL vazio. Se o ambiente já foi criado antes, rode `make run-dev-down` (apaga os dados de dev) antes de subir de novo.

### Ambiente de Testes

#### 1. Copie as variáveis de ambiente de testes:

```bash
cp .envs/.env.test.example .envs/.env.test
```

#### 2. Rode o projeto via docker compose:

```bash
make run-test
```

### Ambiente de Produção

#### 1. Copie as variáveis de ambiente de produção:

```bash
cp .envs/.env.prod.example .envs/.env.prod
```

#### 2. Rode o projeto via docker compose:

```bash
make run-prod
```

## Como executar os testes:

> [!IMPORTANT]
>
> Requisitos:
>
> - Docker (para testes de integração)

> Obs.: O Docker é necessário, pois os testes de integração utilizam Testcontainers.

### Testes unitários:

> Não requerem docker

```bash
make unit-test
```

### Testes de integração:

```bash
make integration-test
```

## Documentação por task

Toda task entregue gera dois arquivos em `docs/`, com o mesmo nome (`<modulo>-<nn>-<descricao>.md`, ex.: `plataforma-03-padrao-schema-banco.md`):

- `docs/tasks/`: contexto, tabela critério de aceite × solução, passos técnicos realizados, decisões e cuidados ao atualizar um ambiente existente.
- `docs/testes/`: data e branch, tabela com o que foi testado, como, resultado esperado, resultado obtido e status, e o que ficou sem verificar.

Convenções transversais ficam em arquivo próprio em `docs/` (ex.: `docs/convencoes-banco.md`).

## Estrutura de pastas

```bash
.
├── docs # Documentação das tasks do backend
│   ├── convencoes-banco.md # Padrão de schema e migrações
│   ├── erros-api.md # Formato dos erros e catálogo de códigos
│   ├── tasks # O que cada task resolveu
│   └── testes # Testes executados em cada task
├── Dockerfile
├── .dockerignore
├── .envs # Variáveis de ambiente do projeto
│   ├── .env.dev.example
│   ├── .env.prod.example
│   └── .env.test.example
├── .gitattributes
├── .github # Configurações do GitHub
│   └── workflows # GitHub Actions (CI/CD)
│       └── ci.yml
├── .gitignore
├── infra # Arquivos relacionados à infraestrutura
│   ├── keycloak
│   │   └── realm-flowsla.json # Realm importado na subida (papéis, clients, gestor inicial, SMTP)
│   ├── postgres
│   │   └── init.sql # Bancos e usuários do PostgreSQL compartilhado
│   ├── rabbitmq
│   │   ├── definitions.json # Topologia do RabbitMQ
│   │   └── enabled_plugins
│   ├── docker-compose.dev.yml
│   ├── docker-compose.prod.yml
│   ├── docker-compose.test.yml
│   └── docker-compose.yml
├── LICENSE
├── Makefile # Atalhos para comandos e scripts
├── .mvn
│   └── wrapper
├── mvnw
├── mvnw.cmd
├── pom.xml # Dependências e configuração do Maven
├── README.md
├── src # Código-fonte e testes
│   ├── main
│   └── test
└── .vscode # Configuração do workspace no VSCode
    └── settings.json
```

<!-- ADICIONAR MAPEAMENTO DE ENDPOINTS -->

<!-- ADICIONAR TABELA EXPLICATIVA DA DOCUMENTAÇÃO DO BACKEND -->

## Repositórios relacionados

| Repositório                                                       | Função             |
| ----------------------------------------------------------------- | ------------------ |
| [docs](https://github.com/gestao-SLA-prestadoras-de-servico/docs) | Documentação geral |

## Autores

|                                                                                                                         |                                                                                                                                |                                                                                                                                     |
| :---------------------------------------------------------------------------------------------------------------------: | :----------------------------------------------------------------------------------------------------------------------------: | :---------------------------------------------------------------------------------------------------------------------------------: |
|                <img src="https://github.com/genzo-dev.png" alt="Gabriel Enzo (genzo-dev)" width="160"/>                 |                        <img src="https://github.com/LuisF3L1P3dev.png" alt="Luis Felipe" width="160"/>                         |                                <img src="https://github.com/rangelro.png" alt="Rangel" width="160"/>                                |
|                                              **Gabriel Enzo (genzo-dev)**                                               |                                                        **Luis Felipe**                                                         |                                                             **Rangel**                                                              |
|                                           <sub>Fullstack web developer</sub>                                            |                                               <sub>Fullstack web developer</sub>                                               |                                                 <sub>Fullstack web developer</sub>                                                  |
| <a href="https://www.linkedin.com/in/genzo-dev/">💼 LinkedIn</a> · <a href="https://github.com/genzo-dev">🐙 GitHub</a> | <a href="https://www.linkedin.com/in/luisfelipe15/">💼 LinkedIn</a> · <a href="https://github.com/LuisF3L1P3dev">🐙 GitHub</a> | <a href="https://www.linkedin.com/in/rangel-rocha-779139228/">💼 LinkedIn</a> · <a href="https://github.com/rangelro">🐙 GitHub</a> |
