# Testes — [plataforma - 07] Preparar o ambiente dev para o frontend Angular e o ms-notificacoes

> Executados em 17/11/2026, no ambiente dev já existente, sem `down -v`.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Compose de dev válido com o serviço novo | `docker compose ... config --services` | Lista inclui `ms-notificacoes` | Inclui `ms-notificacoes` | ✅ |
| 2 | ms-notificacoes sobe na rede do backend | `docker compose ... up -d --build ms-notificacoes` e `curl localhost:8082/actuator/health` | Container `healthy`, status `UP` | `infra-ms-notificacoes-1` `healthy`; `{"status":"UP"}` | ✅ |
| 3 | Client `flowsla-frontend` aponta para 4200 | `kcadm.sh get clients/<id> -r flowsla` | redirect, web origin e post-logout em `localhost:4200` | Os três em `http://localhost:4200`; PKCE `S256` | ✅ |
| 4 | CORS do backend para 4200 | `curl -X OPTIONS localhost:8080/api/usuarios/me` com `Origin: http://localhost:4200` | `Access-Control-Allow-Origin: http://localhost:4200` | Igual ao esperado | ✅ |
| 5 | CORS do ms para 4200 | `curl -X OPTIONS localhost:8082/notificacoes` com o mesmo `Origin` | `Access-Control-Allow-Origin: http://localhost:4200` | Igual ao esperado | ✅ |
| 6 | Sem token, as APIs continuam fechadas | `GET` sem `Authorization` nas duas URLs | 401 | 401 e 401 | ✅ |
| 7 | O front redireciona para o login do Keycloak | Abrir `localhost:4200/app` no navegador | Tela de login do realm `flowsla`, com `code_challenge_method=S256` e `redirect_uri=localhost:4200/app` | Igual ao esperado, sem erro de redirect inválido | ✅ |

## Pendente

- O login completo com o usuário `gestor` e o `GET /api/usuarios/me` com token ainda precisam ser feitos manualmente no navegador.
