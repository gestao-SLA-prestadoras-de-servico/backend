# Plano de testes manuais — o que já está implementado

> Cobre: [plataforma - 06] OpenAPI, [autenticação - 01] a [autenticação - 04], [auditoria - 01] e [cadastro - 01]. Ambiente: **dev**. Tempo estimado: 40–60 min.
>
> Ferramentas: navegador (Swagger UI, console do Keycloak, Mailpit) e terminal (`curl`, `docker`). Os blocos marcados **Terminal** rodam na raiz do backend.

## 0. Preparação (ambiente limpo)

```bash
cp .envs/.env.dev.example .envs/.env.dev   # só se ainda não tiver; confira API_DOCS_ENABLED=true e SWAGGER_URL
make run-dev-down                           # apaga os dados de dev
make run-dev                                # deixe rodando; espere "Started BackendApplication"
```

| O quê | Endereço | Credencial |
| --- | --- | --- |
| Swagger UI | http://localhost:8080/swagger-ui.html | login no Authorize: `gestor` / `dev_gestor_password` |
| Keycloak (console) | http://localhost:8081/admin | `admin` / `dev_keycloak_admin_password` (realm **flowsla**) |
| Mailpit | http://localhost:8025 | — |
| Banco (psql) | `docker exec -it infra-postgres-1 psql -U postgres -d app_principal` | — |

**Como pegar o token para os testes de terminal:** no Swagger, depois do Authorize, execute qualquer endpoint. A caixa **Curl** mostra `-H 'Authorization: Bearer eyJ...'`; copie o token para a variável:

```bash
export T='cole-o-token-aqui'   # vale 5 minutos; se der 401 inesperado, pegue outro
```

### Dados de teste

| Ref. | Tipo | Dados |
| --- | --- | --- |
| **CA** | Cliente | razão social `Refrigeração Norte Ltda`, e-mail `contato@norte.com.br`, endereço `Av. Santos Dumont`, `1500`, compl. `Sala 301`, `Aldeota`, `Fortaleza`, `CE`, `60150160` |
| **CB** | Cliente | `Clima Sul Serviços Ltda`, `contato@climasul.com.br`, `Rua Barão de Studart`, `300`, `Meireles`, `Fortaleza`, `CE`, `60120000` |
| **UT** | Usuário técnico | nome `Ana Técnica`, e-mail `Ana.Tecnica@flowsla.local` (com maiúsculas de propósito), perfil `TECNICO` |
| **UG** | Usuário gestor | `Bruno Gestor`, `bruno.gestor@flowsla.local`, `GESTOR` |
| **UC** | Usuário cliente | `Carla Cliente`, `carla@norte.com.br`, `CLIENTE`, `clienteId` = id de **CA** |
| Senha | dos usuários novos | defina `Teste@12345` pelo link do e-mail |

Anote os ids e `sub` que forem aparecendo:

| Ref. | id | sub | version |
| --- | --- | --- | --- |
| CA | | — | |
| CB | | — | |
| UT | | | — |
| UC | | | — |

---

## 1. Documentação da API e login no Swagger ([plataforma - 06])

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 1.1 | Abrir http://localhost:8080/swagger-ui.html | UI com as tags **Identidade** e **Cadastro** |
| 1.2 | Clicar **Authorize** → `keycloak` → marcar `openid` → **Authorize** | Abre a tela de login do Keycloak (realm flowsla) |
| 1.3 | Entrar com `gestor` / `dev_gestor_password` | Volta ao Swagger como "Authorized" |
| 1.4 | `GET /api/usuarios/me` → Try it out → Execute | 200 com `nome: Gestor Inicial`, `perfil: GESTOR`, `email: gestor@flowsla.local` |
| 1.5 | Abrir qualquer endpoint e olhar **Responses** | Lista 400/401/403/500; `PUT /api/clientes/{id}` também 404 e 409; `POST /api/usuarios` também 409, 422 e 503; exemplos em Problem Details com `codigo` |
| 1.6 | Conferir que o contrato versionado existe: `docs/openapi.json` | Arquivo com os mesmos 6 endpoints |

## 2. Keycloak e token ([autenticação - 01])

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 2.1 | Console do Keycloak → realm **flowsla** → **Realm roles** | `CLIENTE`, `TECNICO`, `GESTOR` |
| 2.2 | **Clients** | `flowsla-frontend`, `flowsla-swagger`, `app-principal-admin` |
| 2.3 | **Users** → `gestor` → **Details** | ID `0199a000-0000-7000-8000-000000000001` |
| 2.4 | **Terminal:** `echo "$T" \| cut -d. -f2 \| python3 -c 'import sys,base64,json;p=sys.stdin.read().strip();print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))),indent=2))'` (não cole o token em sites externos) | `iss` `http://localhost:8081/realms/flowsla`; `aud` contém `app-principal`; `realm_access.roles` contém `GESTOR`; `sub` igual ao 2.3 |
| 2.5 | No psql: `select sub, nome, email, perfil from usuario;` | Uma linha: o gestor inicial, com o mesmo `sub` |

## 3. Autenticação e autorização ([autenticação - 02])

**Terminal:**

```bash
# 3.1 sem token → 401 em Problem Details
curl -i localhost:8080/api/usuarios/me
# 3.2 token adulterado → 401
curl -i localhost:8080/api/usuarios/me -H "Authorization: Bearer ${T%????}AAAA"
# 3.3 CORS: origem do frontend → 200 com Access-Control-Allow-Origin; outra origem → 403
curl -i -X OPTIONS localhost:8080/api/clientes -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: GET'
curl -i -X OPTIONS localhost:8080/api/clientes -H 'Origin: http://malicioso.example' -H 'Access-Control-Request-Method: GET'
```

| # | Resultado esperado |
| --- | --- |
| 3.1 | `401`, `Content-Type: application/problem+json`, `"codigo":"NAO_AUTENTICADO"` |
| 3.2 | `401` `NAO_AUTENTICADO` |
| 3.3 | Primeiro: `200` com `Access-Control-Allow-Origin: http://localhost:5173`. Segundo: `403`, sem esse cabeçalho |

O 403 por papel errado e o 401 de usuário inativo são testados na seção 7, com o técnico criado lá.

## 4. Cadastro de clientes ([cadastro - 01])

Pelo Swagger (tag **Cadastro**), logado como gestor:

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 4.1 | `POST /api/clientes` com **CA** | 201, `ativo: true`, `version: 0`; anote o `id` |
| 4.2 | `POST /api/clientes` com **CB** | 201; anote o `id` |
| 4.3 | `POST /api/clientes` com **CA**, mas `emailContato: "contato.norte.com.br"` | 400 `VALIDACAO`, `erros[0].campo: emailContato` |
| 4.4 | `POST` com **CA**, mas `uf: "ce"` e `cep: "60150-160"` | 400 com `endereco.uf` e `endereco.cep` em `erros` |
| 4.5 | `GET /api/clientes` (sem filtros) | `content` com CB ("Clima Sul…") e depois CA ("Refrigeração…"), em ordem de razão social; `page.size: 20`, `totalElements: 2` |
| 4.6 | `GET /api/clientes?razaoSocial=NORTE` | Só CA (filtro sem diferenciar maiúsculas) |
| 4.7 | `GET /api/clientes?size=1&page=1` | Só CA (a 2ª página de 1 item); `totalPages: 2` |
| 4.8 | `GET /api/clientes?sort=senha` | 400 `VALIDACAO` "Campo de ordenação inválido: senha." |
| 4.9 | `PUT /api/clientes/{id de CB}` com os dados de CB, `razaoSocial: "Clima Sul Serviços EIRELI"` e `version: 0` | 200, `version: 1` |
| 4.10 | Repetir o 4.9 com `razaoSocial: "Clima Sul Outro Nome"` e **ainda** `version: 0` | 409 `CONFLITO_VERSAO` (outro gestor já tinha salvo) |
| 4.11 | `POST /api/clientes/{id de CB}/inativacao` (duas vezes) | 204 nas duas |
| 4.12 | `GET /api/clientes?ativo=false` | Só CB, com `ativo: false` (continua no histórico) |
| 4.13 | `POST /api/clientes/00000000-0000-0000-0000-000000000000/inativacao` | 404 `RECURSO_NAO_ENCONTRADO` |

## 5. Cadastro de usuários ([autenticação - 04] + [autenticação - 03])

Pelo Swagger (tag **Identidade**), `POST /api/usuarios`:

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 5.1 | Corpo **UT** | 201; `email: "ana.tecnica@flowsla.local"` (em minúsculas); `perfil: TECNICO`; `emailDefinicaoSenhaEnviado: true`. Anote `id` e `sub` |
| 5.2 | Corpo **UG** | 201 |
| 5.3 | Corpo **UC** (com `clienteId` de CA) | 201 com `clienteId` de CA |
| 5.4 | **UT** de novo, com `email: "ANA.TECNICA@flowsla.local"` | 409 `USUARIO_JA_EXISTE` |
| 5.5 | `{"nome":"Sem Empresa","email":"sem.empresa@x.local","perfil":"CLIENTE"}` | 422 `CLIENTE_OBRIGATORIO` |
| 5.6 | Igual ao 5.5 com `"clienteId":"00000000-0000-0000-0000-000000000000"` | 422 `CLIENTE_INEXISTENTE` |
| 5.7 | Igual ao 5.5 com `clienteId` de **CB** (inativo) | 422 `CLIENTE_INATIVO` |
| 5.8 | `{"nome":"Técnico Errado","email":"tec.errado@x.local","perfil":"TECNICO","clienteId":"<id de CA>"}` | 422 `CLIENTE_NAO_PERMITIDO` |
| 5.9 | `{"nome":"","email":"invalido","perfil":null}` | 400 `VALIDACAO` com `nome`, `email` e `perfil` |
| 5.10 | Keycloak → **Users** | `ana.tecnica@flowsla.local`, `bruno.gestor@flowsla.local`, `carla@norte.com.br`, e **nenhum** dos e-mails de 5.5 a 5.8 |
| 5.11 | Keycloak → `ana.tecnica@...` → **Role mapping** | `TECNICO` |
| 5.12 | Mailpit | 3 e-mails "Update Your Account" (Ana, Bruno, Carla) |
| 5.13 | No e-mail da **Ana**, clicar no link e definir a senha `Teste@12345` | Keycloak confirma a atualização |
| 5.14 | No e-mail da **Carla**, definir `Teste@12345` | idem |

**Conta órfã (conferência opcional):** no console do Keycloak, crie à mão o usuário `orfa@flowsla.local` (username e e-mail iguais). Depois faça `POST /api/usuarios` com `{"nome":"Conta Órfã","email":"orfa@flowsla.local","perfil":"TECNICO"}`. Esperado: 201, e o `sub` igual ao ID do usuário criado no console (a conta foi reaproveitada, não duplicada).

## 6. Auditoria imutável ([auditoria - 01])

No psql:

```sql
-- 6.1 o cadastro ficou registrado com o sub do gestor, sem dado pessoal
select ocorrido_em, ator_sub, perfil, operacao, entidade_tipo, dados from auditoria order by ocorrido_em;
-- 6.2 não pode alterar
update auditoria set operacao = 'CANCELAMENTO';
-- 6.3 não pode apagar (menos de 5 anos)
delete from auditoria;
-- 6.4 não pode esvaziar
truncate auditoria;
```

| # | Resultado esperado |
| --- | --- |
| 6.1 | Uma linha `CADASTRO_USUARIO` por usuário criado na seção 5 (mais a órfã, se fez), `ator_sub = 0199a000-...-000000000001`, `perfil = GESTOR`, `dados` só com `perfil` e `clienteId` (sem nome nem e-mail) |
| 6.2 | `ERROR: Registro de auditoria não pode ser alterado (RN-124).` |
| 6.3 | `ERROR: Registro de auditoria ... ainda está no período de retenção de 5 anos (RN-124).` |
| 6.4 | `ERROR: A trilha de auditoria não pode ser esvaziada (RN-124).` |

Conectado como `postgres` (superusuário), o 6.2 e o 6.4 são barrados pelo trigger. Como `app_principal`, já dariam `permission denied`.

## 7. Login dos usuários novos e regras por papel ([autenticação - 02], [autenticação - 04] e [autenticação - 05])

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7.1 | Swagger → **Authorize** → **Logout**. Encerrar também a sessão do Keycloak no navegador: abrir http://localhost:8081/realms/flowsla/protocol/openid-connect/logout e confirmar (ou usar uma janela anônima). Depois **Authorize** com `ana.tecnica@flowsla.local` / `Teste@12345` | Tela de login do Keycloak aparece; logado como a técnica |
| 7.2 | `GET /api/usuarios/me` | 200, `perfil: TECNICO`, `clienteId: null` |
| 7.3 | `GET /api/clientes`, `GET /api/usuarios` e `GET /api/tecnicos` | **403** `ACESSO_NEGADO` nos três (só gestor) |
| 7.4 | `POST /api/usuarios` com qualquer corpo | **403** |
| 7.5 | Logout no Swagger e no Keycloak (como no 7.1); login como `carla@norte.com.br` / `Teste@12345`; `GET /api/usuarios/me` | 200, `perfil: CLIENTE`, `clienteId` = id de CA |
| 7.6 | **Usuário inativo:** com a Carla ainda logada, no psql: `update usuario set ativo = false where email = 'carla@norte.com.br';` e repetir o `/me` | **401** `NAO_AUTENTICADO`, mesmo com o token ainda válido |
| 7.7 | Voltar: `update usuario set ativo = true where email = 'carla@norte.com.br';` e repetir | 200 |
| 7.8 | Ainda como Carla: `GET /api/usuarios` e `GET /api/tecnicos` | **403** `ACESSO_NEGADO` nos dois |
| 7.9 | Logout (como no 7.1); login como gestor; `GET /api/usuarios?perfil=GESTOR&ativo=true` | 200, paginado; só gestores ativos, incluindo `Gestor Inicial` e Bruno |
| 7.10 | `GET /api/usuarios` com `clienteId` = id de CA | 200, com a Carla |
| 7.11 | `GET /api/usuarios?perfil=ADMIN` | **400** `VALIDACAO` |
| 7.12 | `GET /api/tecnicos?ativos=true` | 200, paginado; a Ana aparece com `sub`, `nome` e `chamadosNaoFinalizados: 0` |

## 8. Desfecho

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 8.1 | `make unit-test` | Todos passam |
| 8.2 | `make integration-test` (Docker ligado) | Todos passam; o `OpenApiContratoTest` passa (contrato em dia) |
| 8.3 | Opcional: `make run-dev-down` para limpar os dados de teste | — |

Se `make` reclamar de permissão no `target/` (parte dele é do root, criada pelo container de dev), rode `sudo chown -R $USER target` uma vez.

## O que este plano não cobre

- **Compensação** (conta removida quando o banco falha) e **e-mail que falha depois do commit:** precisam de falha simulada; estão nos testes automáticos (`CadastroUsuarioTest`).
- **Timeout e retry da Admin API do Keycloak:** precisam de um Keycloak lento; estão no `KeycloakAdminAdapterTest`.
- **Swagger desligado** em test/prod: `OpenApiDesligadoTest`.
