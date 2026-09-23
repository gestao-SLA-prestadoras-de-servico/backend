# FlowSLA - Backend

Bem-vindo ao repositório de backend do **FlowSLA** - um sistema para automatizar o controle de prazos, evidências e histórico de atendimento relacionados a SLAs (Service Level Agreements), voltado especialmente para empresas prestadoras de serviços técnicos de pequeno e médio porte.

## Qual é o propósito desse repositório?

Este repositório é responsável pelo backend do **FlowSLA**, incluindo as documentações relacionadas especificamente ao backend como mapeamento de endpoints, módulos, entre outros.

## Stack

<!-- ALTERAR PARA A STACK DO PROJETO -->

| Ferramenta                                                                                                 | Versão                              |
| ---------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| [Java](https://docs.oracle.com/en/java/)                                                                   | 21                                  |
| [Spring Boot](https://spring.io/)                                                                          | 4.1.1                               |
| [Spring Data JPA](https://spring.io/projects/spring-data-jpa)                                              | 4.1.1                               |
| [Spring Security](https://spring.io/projects/spring-security)                                              | 7.x                                 |
| [Spring Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html) | 7.x                                 |
| [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)                        | 7.x                                 |
| [PostgreSQL](https://www.postgresql.org/docs/17/)                                                          | 17 (imagem Docker)                  |
| [Maven](https://maven.apache.org/)                                                                         | -                                   |
| [Testcontainers](https://java.testcontainers.org/)                                                         | ^2.0.5 (PostgreSQL + JUnit Jupiter) |
| [Lombok](https://projectlombok.org/)                                                                       | -                                   |
| [Docker/DockerCompose](https://docs.docker.com/)                                                           | -                                   |
| [DockerCompose](https://docs.docker.com/compose/)                                                          | -                                   |

## Como executar o projeto:

> [!IMPORTANT]
>
> Requisitos:
>
> - Docker
> - Docker Compose

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

## Estrutura de pastas

```bash
.
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
