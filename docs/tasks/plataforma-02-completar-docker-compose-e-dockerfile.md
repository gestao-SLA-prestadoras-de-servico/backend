# [plataforma - 02] Completar o Docker Compose e o Dockerfile com as dependências do app-principal

> Requisitos: seção 3, RN-58, RN-80 · Depende de: [plataforma - 01]

## Contexto

O `infra/docker-compose.yml` subia só o backend e o PostgreSQL, com arquivos de sobreposição para dev, test e prod e variáveis em `.envs/`. O app-principal também depende de RabbitMQ, MinIO, Keycloak e Mailpit, e todos precisam subir pelo `make run-dev`, do mesmo jeito para toda a equipe. Além disso, o MinIO não pode ficar acessível ao navegador e o banco do app-principal não pode acessar o banco do ms-notificacoes.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `make run-dev` sobe backend, PostgreSQL, RabbitMQ, MinIO, Keycloak e Mailpit com healthcheck saudável | Os quatro serviços novos entraram no `infra/docker-compose.yml`, cada um com `healthcheck`: RabbitMQ (`rabbitmq-diagnostics ping` + topologia importada), MinIO (`/minio/health/live`), Keycloak (`/health/ready` na porta de gerenciamento 9000) e Mailpit (`mailpit readyz`). |
| O backend só sobe depois que as dependências estão saudáveis | `depends_on` do backend com `condition: service_healthy` para os cinco serviços. O Keycloak também espera o PostgreSQL. |
| PostgreSQL com bancos `app_principal` e `notificacoes`, usuários separados, sem acesso do app-principal ao ms-notificacoes | `infra/postgres/init.sql`, montado em `/docker-entrypoint-initdb.d`, cria um banco por dono e tira o `CONNECT` padrão do `PUBLIC` (`REVOKE ALL ON DATABASE ... FROM PUBLIC`). Assim, só o dono e o superusuário conectam em cada banco. Usuários e senhas vêm de `.envs/` via `\getenv`, sem segredo no SQL. Também foi criado o banco `keycloak` (ver Decisões). |
| A porta S3 do MinIO não está publicada no host | Nenhum arquivo de compose publica a porta 9000 do MinIO. Ela fica acessível só pela rede interna do Docker. |
| RabbitMQ sobe com a topologia de `infra/rabbitmq/definitions.json` | O arquivo é montado no container e importado com `rabbitmqctl import_definitions` no `post_start` do serviço, executado como usuário `rabbitmq`. O healthcheck só fica saudável depois da importação. |
| `.envs/*.example` com todas as variáveis novas, sem segredos reais | Os três arquivos ganharam as variáveis de PostgreSQL (superusuário, notificações, Keycloak), RabbitMQ, MinIO e Keycloak, com senhas de exemplo (`troque-esta-senha` em prod). |
| Imagem de produção com usuário não-root e healthcheck | O estágio `production` do `Dockerfile` cria o usuário `app`, roda com `USER app` e tem `HEALTHCHECK` com `curl` em `/actuator/health`. |

## Passos técnicos realizados

- [x] **Adicionar ao `infra/docker-compose.yml` os serviços RabbitMQ (management + plugin Prometheus), MinIO, Keycloak e Mailpit**
  - `rabbitmq:4.1-management-alpine`, com `infra/rabbitmq/enabled_plugins` ativando `rabbitmq_management` e `rabbitmq_prometheus`.
  - `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` (ver Decisões).
  - `quay.io/keycloak/keycloak:26.4`, usando o banco `keycloak` no PostgreSQL.
  - `axllent/mailpit:v1.27`.
  - Todos ficam na rede `flowsla-<ENVIRONMENT>`.

- [x] **Ajustar `docker-compose.dev.yml`, `docker-compose.test.yml` e `docker-compose.prod.yml` (volumes, portas expostas só em dev)**
  - **dev:** volumes nomeados para PostgreSQL, RabbitMQ e MinIO; portas publicadas: RabbitMQ management `15672`, console do MinIO `9001`, Keycloak `8081` e interface do Mailpit `8025`; Keycloak em `start-dev`.
  - **test:** sem volumes persistentes (PostgreSQL em `tmpfs`), nenhuma porta de infraestrutura publicada; Keycloak em `start-dev`.
  - **prod:** volumes nomeados, nenhuma porta de infraestrutura publicada; Keycloak em `start`.

- [x] **Criar `infra/postgres/init.sql` com os dois bancos e os dois usuários e montar em `/docker-entrypoint-initdb.d`**
  Bancos `app_principal`, `notificacoes` e `keycloak`, cada um pertencente ao seu usuário e com `REVOKE ALL ... FROM PUBLIC`. O script só roda com o volume vazio (comportamento da imagem oficial do PostgreSQL).

- [x] **Montar `infra/rabbitmq/definitions.json` (conteúdo definido em [outbox - 02])**
  Por enquanto, topologia mínima: vhost `/` e exchange `outbox.eventos` (topic). Não contém usuários nem senhas. A [outbox - 02] completa o conteúdo.

- [x] **Deixar o console do MinIO exposto só em dev**
  `9001:9001` só no `docker-compose.dev.yml`.

- [x] **Adicionar as variáveis novas em `.envs/.env.dev.example`, `.env.test.example` e `.env.prod.example`**

- [x] **No Dockerfile, criar usuário não-root no estágio production e adicionar HEALTHCHECK via Actuator**
  A imagem `eclipse-temurin:21-jre` não tem `curl` nem `wget`, então o `curl` é instalado no estágio. O backend passou a ler a conexão do RabbitMQ de `.envs/` (`RABBITMQ_*`), para que `/actuator/health` fique `UP`.

- [x] **Combinar com o repositório do ms-notificacoes onde roda o PostgreSQL e o RabbitMQ compartilhados**
  Combinado: o PostgreSQL e o RabbitMQ compartilhados rodam **neste** compose. O ms-notificacoes entra na rede como externa:

  ```yaml
  networks:
    default:
      name: flowsla-dev   # flowsla-test / flowsla-prod conforme o ambiente
      external: true
  ```

  Ele se conecta em `postgres:5432/notificacoes`, com `NOTIFICACOES_DB_USER`/`NOTIFICACOES_DB_PASSWORD`, e em `rabbitmq:5672`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Imagem do MinIO | `pgsty/minio` com tag fixa | A imagem oficial `minio/minio` saiu do Docker Hub. O build do Pigsty é recente, permite fixar versão e tem o console web. |
| Banco do Keycloak | Terceiro banco `keycloak` no PostgreSQL compartilhado, com usuário próprio | O modo `start` (prod) do Keycloak exige banco real. O app-principal também não acessa esse banco. |
| Importação das definições do RabbitMQ | `post_start` + `rabbitmqctl import_definitions`, em vez de `load_definitions` | Com `load_definitions` no boot, o RabbitMQ não cria o usuário de `RABBITMQ_DEFAULT_USER` (testado). Colocar o usuário no `definitions.json` exporia o hash da senha no repositório. O hook roda como `rabbitmq`, porque como root o CLI cria o `.erlang.cookie` com dono errado e o RabbitMQ não sobe (testado). Requer Docker Compose 2.30 ou mais recente. |
| Rede | Nome fixo `flowsla-<ENVIRONMENT>` | Permite ao ms-notificacoes entrar na rede sem conflito entre ambientes. |

## Atenção ao atualizar um ambiente existente

O `init.sql` só roda quando o volume do PostgreSQL está vazio. Em uma máquina que já subiu o ambiente antes, recrie os volumes (isso **apaga os dados** do ambiente):

```bash
make run-dev-down   # usa down -v
make build-dev
```

## Como verificar

Ver `docs/testes/plataforma-02-completar-docker-compose-e-dockerfile.md`.
