# Testes — [plataforma - 02] Completar o Docker Compose e o Dockerfile com as dependências do app-principal

> Executados em 12/10/2026, na branch `feat/plataforma-02-docker-compose` (alterações ainda não commitadas), em Fedora com SELinux `Enforcing`, Docker e Docker Compose 5.5.1. Ambiente dev recriado do zero (`make run-dev-down` + `.envs/.env.dev` copiado do novo `.env.dev.example`).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Testes unitários continuam passando | `make unit-test` | `ModularidadeTest` passa | `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS | ✅ |
| 2 | Testes de integração continuam passando | `make integration-test` | Contexto sobe (com as propriedades novas do RabbitMQ) e conecta no Postgres | `Tests run: 1, Failures: 0, Errors: 0` — BUILD SUCCESS | ✅ |
| 3 | Compose válido nos três ambientes | `docker compose --env-file .envs/.env.<env> -f infra/docker-compose.yml -f infra/docker-compose.<env>.yml config -q` (dev, test, prod) | Sem erros | dev, test e prod: OK | ✅ |
| 4 | Ambiente dev sobe com todos os serviços saudáveis | `docker compose ... up --build -d` (mesmos arquivos do `make run-dev`) e `docker compose ps` | backend, postgres, rabbitmq, minio, keycloak e mailpit em execução; dependências `healthy` | postgres, rabbitmq, minio, keycloak e mailpit `healthy`; backend `Up` | ✅ |
| 5 | Backend espera as dependências | `docker inspect -f '{{.State.StartedAt}}'` de cada container | Backend inicia por último, depois de todos saudáveis | postgres/minio/mailpit 21:11:54 → keycloak 21:11:59 → rabbitmq 21:12:12 → backend 21:12:30 | ✅ |
| 6 | Backend saudável | `curl localhost:8080/actuator/health` | `UP` (agora conecta no RabbitMQ) | `{"status":"UP"}`; log: `Started BackendApplication in 4.572 seconds` | ✅ |
| 7 | Bancos lógicos e donos | `psql -U postgres` consultando `pg_database` | `app_principal`, `notificacoes` e `keycloak`, cada um com seu usuário e sem `CONNECT` para `PUBLIC` | `app_principal\|app_principal\|{app_principal=CTc/app_principal}`, idem para `notificacoes` e `keycloak` | ✅ |
| 8 | Usuário do app-principal não acessa o banco do ms-notificacoes | `psql -U app_principal -d notificacoes` | Conexão negada | `User does not have CONNECT privilege.` | ✅ |
| 9 | Usuário do app-principal não acessa o banco do Keycloak | `psql -U app_principal -d keycloak` | Conexão negada | `User does not have CONNECT privilege.` | ✅ |
| 10 | Cada usuário acessa o próprio banco | `psql -U app_principal -d app_principal` e `psql -U notificacoes -d notificacoes` | Conecta | Ambos retornaram `1` | ✅ |
| 11 | Porta S3 do MinIO não publicada no host | `docker port infra-minio-1` e `curl localhost:9000/minio/health/live` | Só a 9001 publicada; 9000 inacessível pelo host | Só `9001/tcp -> 0.0.0.0:9001`; `localhost:9000` sem resposta (`000`) | ✅ |
| 12 | MinIO acessível pela rede interna | `curl http://minio:9000/minio/health/live` de dentro do container do backend | `200` | `200` | ✅ |
| 13 | Console do MinIO exposto em dev | `curl localhost:9001` | `200` | `200` | ✅ |
| 14 | RabbitMQ com topologia de `definitions.json` | `rabbitmqctl list_exchanges name type` | Exchange `outbox.eventos` (topic) | `outbox.eventos	topic` | ✅ |
| 15 | Usuário do RabbitMQ criado a partir de `.envs/` | `rabbitmqctl list_users` | Usuário de `RABBITMQ_USER` | `app_principal [administrator]` | ✅ |
| 16 | RabbitMQ com management e Prometheus | `rabbitmq-plugins list -E` e `curl -u ... localhost:15672/api/overview` | Plugins ativos; API do management responde | `rabbitmq_management 4.1.8` e `rabbitmq_prometheus 4.1.8` ativos; API `200` | ✅ |
| 17 | Keycloak acessível em dev | `curl localhost:8081/realms/master` | `200` | `200` | ✅ |
| 18 | Mailpit acessível em dev | `curl localhost:8025` | `200` | `200` | ✅ |
| 19 | Portas de infraestrutura publicadas só em dev | `docker compose ... config` de test e prod, contando `published` | Só a porta do backend (`8080`) | test e prod: 1 porta publicada (`8080`) | ✅ |
| 20 | Imagem de produção roda com usuário não-root | `docker build --target production`, `docker image inspect -f '{{.Config.User}}'` e `id -un` no container | Usuário `app` | `USER=app`; `whoami=app` | ✅ |
| 21 | Imagem de produção tem healthcheck via Actuator | Container da imagem de produção na rede `flowsla-dev` com `.env.dev`; `docker inspect -f '{{.State.Health.Status}}'` | `healthy` | `HEALTHCHECK curl -fs http://localhost:8080/actuator/health`; `healthy` após ~15 s | ✅ |
| 22 | `.envs/*.example` com as variáveis novas e sem segredos reais | Leitura dos três arquivos e `grep password` | Todas as variáveis usadas no compose; senhas de exemplo | Variáveis de Postgres, notificações, Keycloak, RabbitMQ e MinIO nos três; prod com `troque-esta-senha` | ✅ |
| 23 | Nenhum segredo nos arquivos versionados de configuração | `grep` em `application.properties`, `init.sql` e `definitions.json` | Só placeholders `${...}` / `\getenv` | Nenhum valor literal | ✅ |

## Observações

1. **Não verificado:** o ms-notificacoes conectando de fato pela rede `flowsla-dev`, porque o repositório dele não está nesta máquina. Do lado deste repo, a rede, o banco e o usuário foram verificados (testes 7, 8 e 10).
2. **Não verificado:** subir os ambientes test e prod completos. Só a configuração do compose foi validada (testes 3 e 19), e a imagem de produção foi testada isoladamente (testes 20 e 21).
