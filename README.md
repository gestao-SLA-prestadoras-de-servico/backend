# NOME DO PROJETO

Bem-vindo ao repositório de backend do **[NOME DO PROJETO]** - [explicação resumida do projeto]

> Para uma visão geral do projeto, acesse o [inserir link do repositório de hub].

## Qual o propósito desse repositório?

Este repositório é responsável pelo backend do **[NOME DO PROJETO]**, incluindo as documentações relacionadas especificamente ao backend como mapeamento de endpoints, módulos, entre outros.

## Stack

<!-- ALTERAR PARA A STACK DO PROJETO -->

| Ferramenta                                                               | Versão             |
| ------------------------------------------------------------------------ | ------------------ |
| [NestJS](https://nestjs.com/)                                            | ^11.0.1            |
| [TypeScript](https://www.typescriptlang.org/)                            | ^5.7.3             |
| [PostgreSQL](https://www.postgresql.org/)                                | 17 (imagem Docker) |
| [TypeORM](https://typeorm.io/)                                           | ^1.0.0             |
| [Docker](https://docs.docker.com/)                                       | -                  |
| [Jest](https://jestjs.io/pt-BR/)                                         | ^30.0.0            |
| [Joi](https://joi.dev/)                                                  | ^18.2.3            |
| Swagger (@nestjs/swagger) (https://docs.nestjs.com/openapi/introduction) | ^11.4.6            |
| Resend (https://resend.com/)                                             | ^6.18.1            |
| bcrypt (https://www.npmjs.com/package/bcrypt)                            | ^6.0.0             |

## Como rodar o projeto:

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

#### 1. Copie as variáveis de ambiente de tprodução:

```bash
cp .envs/.env.prod.example .envs/.env.prod
```

#### 2. Rode o projeto via docker compose:

```bash
make run-prod
```

#### ATENÇÃO:

Caso seja necessário rebuildar o ambiente de produção, utilize o comando:

```bash
make build-prod
```

<!-- ADICIONAR ESTRUTURA DE PASTAS -->

<!-- ADICIONAR MAPEAMENTO DE ENDPOINTS -->

<!-- ADICIONAR TABELA EXPLICATIVA DA DOCUMENTAÇÃO DO BACKEND -->

## Repositórios relacionados

| Repositório                                                       | Função             |
| ----------------------------------------------------------------- | ------------------ |
| [docs](https://github.com/gestao-SLA-prestadoras-de-servico/docs) | Documentação geral |

## Autores

|                                                                                                                         |                                                                                 |                                                                             |
| :---------------------------------------------------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: | :-------------------------------------------------------------------------: |
|                <img src="https://github.com/genzo-dev.png" alt="Gabriel Enzo (genzo-dev)" width="160"/>                 | <img src="https://github.com/LuisF3L1P3dev.png" alt="Luis Felipe" width="160"/> |    <img src="https://github.com/rangelro.png" alt="Rangel" width="160"/>    |
|                                              **Gabriel Enzo (genzo-dev)**                                               |                                 **Luis Felipe**                                 |                                 **Rangel**                                  |
|                                           <sub>Fullstack web developer</sub>                                            |                       <sub>Fullstack web developer</sub>                        |                     <sub>Fullstack web developer</sub>                      |
| <a href="https://www.linkedin.com/in/genzo-dev/">💼 LinkedIn</a> · <a href="https://github.com/genzo-dev">🐙 GitHub</a> |   <a href="URL_LINKEDIN">💼 LinkedIn</a> · <a href="URL_GITHUB">🐙 GitHub</a>   | <a href="URL_LINKEDIN">💼 LinkedIn</a> · <a href="URL_GITHUB">🐙 GitHub</a> |
