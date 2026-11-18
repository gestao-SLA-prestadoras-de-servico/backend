# [plataforma - 07] Preparar o ambiente dev para o frontend Angular e o ms-notificacoes

> Depende de: [plataforma - 02], [autenticacao - 01..06]

## Contexto

O frontend é Angular e roda em `http://localhost:4200`, mas o `FRONTEND_URL` apontava para `5173`, uma porta do Vite. Essa variável controla o CORS do backend (`app.cors.origem`) e o client `flowsla-frontend` do realm (redirect URIs, web origins e post-logout).

O ms-notificacoes só subia pela stack própria dele, com outro RabbitMQ e outro Postgres. Nessa stack ele não recebia os eventos do backend.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| O navegador em `localhost:4200` chama a API sem erro de CORS | `FRONTEND_URL=http://localhost:4200` no `.env.dev` e nos três `.env.*.example`. |
| O Keycloak aceita o redirect do front em 4200 | O realm só é importado uma vez. No dev já existente, o client `flowsla-frontend` foi atualizado com `kcadm.sh`. Num volume novo, o import já usa o valor novo. |
| `make run-dev` também sobe o ms-notificacoes, ligado à mesma infra | Novo serviço `ms-notificacoes` em `infra/docker-compose.dev.yml`. Ele usa `build: ../../ms-notifications`, na rede `flowsla-dev`, com o banco `notificacoes`, o RabbitMQ, o Mailpit e o issuer do Keycloak do backend. |
| O ms não colide com a porta do Keycloak | O host publica a `8082`, mapeada para a `8081` do container. A `8081` do host continua sendo do Keycloak. |

## Decisões

- **Só em dev.** O ms foi incluído apenas no override de dev, porque depende do repositório irmão `../ms-notifications`. Test e prod continuam como estavam.
- **Nenhuma variável nova.** O serviço reaproveita as do backend:
  - `NOTIFICACOES_DB_*` para o banco;
  - `RABBITMQ_USER` repassada como `RABBITMQ_USERNAME`;
  - `SMTP_*` repassadas como `MAIL_*`;
  - `FRONTEND_URL` repassada como `CORS_ALLOWED_ORIGINS`.

## Como aplicar num dev já existente (sem `down -v`)

1. Recriar os containers com o compose atualizado: `docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up -d --build ms-notificacoes backend-app`.
2. No console do Keycloak (`localhost:8081`, realm `flowsla`, client `flowsla-frontend`), trocar `5173` por `4200` em Valid redirect URIs, Web origins e Valid post logout redirect URIs.
