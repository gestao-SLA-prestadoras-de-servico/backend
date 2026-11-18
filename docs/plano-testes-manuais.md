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

## 4A. Cadastro de contratos ([cadastro - 02])

Logado como gestor. "Hoje" é a data do sistema no fuso `America/Fortaleza`.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 4A.1 | `POST /api/contratos` com `clienteId` = CA, `codigo: "CT-NORTE-01"`, início 1º de janeiro e fim 31 de dezembro do ano corrente | 201; anote o `id` (**KA**) |
| 4A.2 | `POST /api/contratos` com `clienteId` = CA, `codigo: "CT-NORTE-00"`, vigência no ano passado inteiro | 201 (**KV**, vencido); RN-06: CA tem dois contratos |
| 4A.3 | Repetir o 4A.1 | 409 `CODIGO_CONTRATO_JA_EXISTE` |
| 4A.4 | `POST` com `codigo: "CT-X"`, início `2026-02-01` e fim `2026-01-31` | 400 `VALIDACAO`, `erros[0].campo: fimVigencia` |
| 4A.5 | `POST` com `clienteId` = CB (inativo desde o 4.11), `codigo: "CT-SUL-01"` | 422 `CLIENTE_INATIVO` |
| 4A.6 | `POST` com `clienteId: "00000000-0000-0000-0000-000000000000"` | 422 `CLIENTE_INEXISTENTE` |
| 4A.7 | `GET /api/contratos?clienteId={CA}` | KV e KA, nessa ordem (por código) |
| 4A.8 | `GET /api/contratos?clienteId={CA}&vigente=true` e depois `vigente=false` | Só KA; depois só KV |

## 4B. Políticas de SLA versionadas ([cadastro - 03])

Logado como gestor. Prazos em minutos de janela (540 = 1 dia útil).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 4B.1 | `GET /api/politicas` | A política `Padrão` (seed), versão 1: alta 120/540/1080, normal 540/1620/2700, `prioridadePadrao: NORMAL`, `criadaPor: null` |
| 4B.2 | `POST /api/politicas` com `nome: "Ouro"`, `prioridadePadrao: "ALTA"` e `prazos` `{"alta":{"resposta":60,"campo":300,"resolucao":900},"normal":{"resposta":300,"campo":900,"resolucao":1800}}` | 201, `versaoAtual.numero: 1`; anote o `id` (**PO**) |
| 4B.3 | Repetir o 4B.2 | 409 `NOME_POLITICA_JA_EXISTE` |
| 4B.4 | `POST /api/politicas` com outro nome e `alta.resposta` igual a `alta.campo` (ex.: 300 e 300) | 422 `ORDEM_PRAZOS_INVALIDA`, mensagem citando `ALTA` |
| 4B.5 | `POST /api/politicas/{PO}/versoes` com `prioridadePadrao: "NORMAL"` e `normal.campo` maior que `normal.resolucao` | 422 `ORDEM_PRAZOS_INVALIDA`, citando `NORMAL` |
| 4B.6 | `POST /api/politicas/{PO}/versoes` com os prazos do 4B.2 trocando `alta.resposta` para 90 | 201, `numero: 2` |
| 4B.7 | `GET /api/politicas/{PO}/versoes` | Versões 2 e 1, nessa ordem; a 1 ainda com `alta.resposta: 60` |
| 4B.8 | No psql: `update politica_sla_versao set resposta_alta = 1;` | Erro "Versão de política de SLA não pode ser alterada nem removida (RN-04)." |
| 4B.9 | No psql: `select operacao, entidade_tipo, dados from auditoria where entidade_id = '{PO}';` | 2 linhas `ALTERACAO_POLITICA` / `POLITICA_SLA`, com `versao` 1 e 2 |

## 4C. Política do contrato ([cadastro - 04])

Logado como gestor; usa KA (4A.1) e PO (4B.2).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 4C.1 | `GET /api/contratos?clienteId={CA}` | KA com `politicaId: null` (ainda não recebe chamados, RN-03) |
| 4C.2 | `PUT /api/contratos/{KA}/politica` com `{"politicaId": "0199a000-0000-7000-8000-00000000c001"}` (Padrão) | 200, `politicaId` da Padrão |
| 4C.3 | Repetir o 4C.2 | 200, sem novo registro de auditoria |
| 4C.4 | `PUT /api/contratos/{KA}/politica` com `{"politicaId": "{PO}"}` | 200, `politicaId` = PO (substitui a Padrão) |
| 4C.5 | `PUT /api/contratos/{KA}/politica` com um `politicaId` aleatório | 422 `POLITICA_INEXISTENTE` |
| 4C.6 | `PUT /api/contratos/00000000-0000-0000-0000-000000000000/politica` com a Padrão | 404 `RECURSO_NAO_ENCONTRADO` |
| 4C.7 | No psql: `select dados from auditoria where entidade_tipo = 'CONTRATO' and entidade_id = '{KA}' order by ocorrido_em;` | 2 linhas `ALTERACAO_POLITICA`: `politicaAnterior` nula → Padrão, e Padrão → PO |

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
| 7.5a | Ainda como Carla: `GET /api/me/contratos` e depois `GET /api/contratos` | Só KA (o vencido KV não aparece) ([cadastro - 02]); depois **403** |
| 7.6 | **Usuário inativo:** com a Carla ainda logada, no psql: `update usuario set ativo = false where email = 'carla@norte.com.br';` e repetir o `/me` | **401** `NAO_AUTENTICADO`, mesmo com o token ainda válido |
| 7.7 | Voltar: `update usuario set ativo = true where email = 'carla@norte.com.br';` e repetir | 200 |
| 7.8 | Ainda como Carla: `GET /api/usuarios` e `GET /api/tecnicos` | **403** `ACESSO_NEGADO` nos dois |
| 7.9 | Logout (como no 7.1); login como gestor; `GET /api/usuarios?perfil=GESTOR&ativo=true` | 200, paginado; só gestores ativos, incluindo `Gestor Inicial` e Bruno |
| 7.10 | `GET /api/usuarios` com `clienteId` = id de CA | 200, com a Carla |
| 7.11 | `GET /api/usuarios?perfil=ADMIN` | **400** `VALIDACAO` |
| 7.12 | `GET /api/tecnicos?ativos=true` | 200, paginado; a Ana aparece com `sub`, `nome` e `chamadosNaoFinalizados: 0` |

## 7A. Inativação de usuário ([autenticação - 06])

Faça logado como gestor. Cadastre antes um técnico descartável e anote o `id` dele (`TD`); guarde também o `id` do gestor em `/api/usuarios/me`.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7A.1 | `POST /api/usuarios/{id do gestor}/inativacao` | **422** `AUTOINATIVACAO_NAO_PERMITIDA` |
| 7A.2 | `POST /api/usuarios/{TD}/inativacao` com `{"substitutoId":"<TD>"}` | **422** `SUBSTITUTO_INVALIDO` |
| 7A.3 | `POST /api/usuarios/{TD}/inativacao` sem corpo | 204 |
| 7A.4 | Repetir o 7A.3 | 204, sem nova linha na auditoria |
| 7A.5 | Keycloak → **Users** → o técnico | **Enabled** desligado |
| 7A.6 | psql: `select ativo from usuario where id = '<TD>';` e `select operacao, dados from auditoria where entidade_id = '<TD>';` | `ativo = f`; uma linha `INATIVACAO_USUARIO` |
| 7A.7 | Inativar um cliente com usuários e repetir o 7A.5 e o 7A.6 para um deles | Conta desabilitada; auditoria com `ator_sub` nulo |

## 7B. Topologia do RabbitMQ ([outbox - 02])

Management em http://localhost:15672, com `RABBITMQ_USER`/`RABBITMQ_PASSWORD` de `.envs/.env.dev`. O ms-notificacoes não precisa estar rodando.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7B.1 | **Exchanges** | `notificacoes`, `notificacoes.dlx`, `evidencias` e `evidencias.dlx`, todas `direct` |
| 7B.2 | **Queues and Streams** | As 6 filas; as DLQs de notificação com `x-message-ttl` 604800000; as 3 filas principais com `x-dead-letter-exchange` |
| 7B.3 | Exchange `notificacoes` → **Publish message**, routing key `email`, *Delivery mode* persistente | `Message published.`; `notificacoes.email` com 1 mensagem |
| 7B.4 | `docker compose ... restart rabbitmq` e conferir a fila | A mensagem continua lá |
| 7B.5 | Fila `notificacoes.email` → **Purge Messages** | Fila vazia (limpeza do teste) |

## 7C. Publicador da outbox ([outbox - 03])

Nenhum módulo grava eventos ainda; o evento entra direto pelo psql (`<ID>` = um UUID qualquer):

```sql
insert into outbox_eventos (id, evento_id, tipo, exchange, routing_key, payload, criado_em)
values (gen_random_uuid(), '<ID>', 'ATRIBUICAO', 'notificacoes', 'interna', '{"eventoId":"<ID>"}', now());
```

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7C.1 | Inserir o evento e esperar 5 s | `publicado_em` preenchido; `notificacoes.interna` com 1 mensagem, `message_id` = `<ID>` |
| 7C.2 | `docker compose ... stop rabbitmq`, inserir outro evento (routing key `email`) e esperar 10 s | `publicado_em` nulo, `tentativas` ≥ 1, `ultimo_erro` preenchido |
| 7C.3 | `docker compose ... start rabbitmq` e esperar | O evento é publicado; `notificacoes.email` com 1 mensagem |
| 7C.4 | Inserir um evento com routing key `inexistente` e, depois, um com routing key `interna` ([outbox - 05]) | O primeiro fica pendente com `ultimo_erro` "Sem fila de destino" e `tentativas` crescendo a cada ciclo; o `interna` é publicado mesmo assim. Apague o primeiro (`delete from outbox_eventos where evento_id = '<ID>'`) |
| 7C.5 | Esvaziar as filas usadas (como no 7B.5) | Filas vazias |

As métricas `outbox_*` estão em `/actuator/prometheus`, que hoje exige token.

## 7D. Chamados: abertura, consulta e prioridade ([chamado - 03] a [chamado - 05])

Usa KA (vigente, com a política PO desde o 4C.4), KV (vencido), UC (cliente de CA) e UT (técnico). Os prazos esperados dependem dos minutos da PO e da hora da abertura; confira-os pela regra da janela (segunda a sexta, 08h às 17h).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7D.1 | Logado como **UC**: `POST /api/chamados` com `{"contratoId": "{KA}", "titulo": "Ar-condicionado parado", "descricao": "Não liga", "local": "Sala 3", "prioridadeSugerida": "ALTA"}` | 201; `numero`, `estado: ABERTO`, `prioridadeSugerida: ALTA`, `prioridade` = a padrão da PO; 3 `relogios` com `venceEm`. Anote o `id` (**CH**) |
| 7D.2 | Como **UC**, o mesmo corpo com `contratoId` = KV | 422 `CONTRATO_FORA_VIGENCIA` |
| 7D.3 | Como **UC**, `contratoId` de um contrato de CB (crie um no 4A, se preciso) | 404 `RECURSO_NAO_ENCONTRADO` |
| 7D.4 | Como **UC**: `GET /api/chamados` e `GET /api/chamados/contagem` | Só os chamados de CA; `ABERTO` ≥ 1 |
| 7D.5 | Como **UT**: `GET /api/chamados/{CH}` | 404 (não está atribuído a ele) |
| 7D.6 | No psql: `update chamado set tecnico_sub = '<sub de UT>', estado = 'ATRIBUIDO' where id = '{CH}';` e repetir o 7D.5 | 200, sem `contratoId`, `politicaVersaoId` nem `prioridadePadrao` |
| 7D.7 | Como gestor: `PUT /api/chamados/{CH}/prioridade` com `{"prioridade": "ALTA"}` (ou `NORMAL`, se a padrão da PO for ALTA) | 200; os relógios ainda correndo com os prazos da nova prioridade |
| 7D.8 | Repetir o 7D.7 | 200, sem novo registro de auditoria `ALTERACAO_PRIORIDADE` |
| 7D.9 | `GET /api/chamados/{CH}/historico` como gestor e como **UC** | Gestor: `ABERTURA` e `ALTERACAO_PRIORIDADE`; UC: só `ABERTURA` |
| 7D.10 | Como gestor: `GET /api/chamados?clienteId={CA}&prioridade=ALTA` e `GET /api/chamados?sort=proximoVencimento,asc` | CH na primeira; na segunda, ordenados pelo prazo mais próximo |
| 7D.11 | `GET /api/chamados/{CH}/sla` como gestor, **UC** e **UT** | 200 `{"apurado": false}` para os três (ainda não encerrado) |
| 7D.12 | No psql: `update chamado set estado = 'RESOLVIDO' where id = '{CH}';` e repetir o 7D.7 | 422 `TRANSICAO_INVALIDA` |

## 7E. Posicionamento e atribuição ([chamado - 06] e [chamado - 07])

Usa CH (7D.1, volte-o para `ABERTO` e `tecnico_sub` nulo pelo psql, se preciso), UT e um segundo técnico (**UT2**, cadastrado como no 5.1). Os eventos ficam em `outbox_eventos` e são publicados no RabbitMQ (seção 7C); o e-mail só chega ao Mailpit com o ms-notificacoes rodando.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7E.1 | Como gestor: `POST /api/chamados/{CH}/posicionamento` com `{"texto": "Recebemos e enviaremos um técnico."}` | 200; `posicionamento` preenchido; relógio RESPOSTA `CUMPRIDO` (ou `VIOLADO`, se o prazo já passou) |
| 7E.2 | Repetir o 7E.1 | 422 `POSICIONAMENTO_JA_REGISTRADO` |
| 7E.3 | No psql: `select tipo, routing_key, payload->>'textoPosicionamento' from outbox_eventos where payload->'chamado'->>'id' = '{CH}';` | 1 linha `POSICIONAMENTO` / `email` com o texto |
| 7E.4 | `GET /api/chamados/{CH}` (anote `version`) e `POST /api/chamados/{CH}/atribuicao` com `{"tecnicoSub": "<sub de UT>", "version": <version>}` | 200; `estado: ATRIBUIDO`, `tecnicoSub` = UT; os `venceEm` dos relógios iguais aos de antes |
| 7E.5 | Repetir o 7E.4 com a mesma `version` | 409 `CONFLITO_VERSAO` |
| 7E.6 | Atribuir de novo, com a `version` atual, a UT | 422 `REATRIBUICAO_NAO_PERMITIDA` (mesmo técnico) |
| 7E.7 | Atribuir, com a `version` atual, a UT2 | 200, `tecnicoSub` = UT2; na outbox, `ATRIBUICAO` para UT2 e `REMOCAO` para UT |
| 7E.8 | Como **UT**: `GET /api/chamados/{CH}` | 404 (foi removido) |
| 7E.9 | Como gestor: `GET /api/tecnicos` | UT2 com `chamadosNaoFinalizados` ≥ 1 |

## 7F. Preferência, início do atendimento e cancelamento ([chamado - 08] a [chamado - 10])

Usa CH atribuído a UT2 (7E.7), um segundo gestor (**UG**, do 5) e um chamado novo (**CH2**, aberto como no 7D.1).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7F.1 | Como gestor: `PUT /api/chamados/{CH}/preferencia-notificacao` e depois `GET /api/chamados/{CH}` | 204; `notificarConclusao: true` |
| 7F.2 | Como **UG**: `GET /api/chamados/{CH}` | `notificarConclusao: false` (a preferência é individual) |
| 7F.3 | Repetir o 7F.1; depois `DELETE` | 204 nos dois; no psql, 2 linhas `ALTERACAO_PREFERENCIA_NOTIFICACAO` para CH (a repetição não audita) |
| 7F.4 | Como **UT2**: `PUT /api/chamados/{CH}/atendimento/descricao` com `{"descricao": "Trocado o capacitor"}` | 422 `TRANSICAO_INVALIDA` (atendimento não iniciado) |
| 7F.5 | Como **UT2**: `POST /api/chamados/{CH}/atendimento/inicio` | 200; `estado: EM_ATENDIMENTO`; relógio CAMPO `CUMPRIDO`; 1 atendimento `EM_ANDAMENTO` |
| 7F.6 | Repetir o 7F.4 | 200; `atendimentos[0].descricaoTecnica` preenchida |
| 7F.7 | Como **UT** (não atribuído): `POST /api/chamados/{CH}/atendimento/inicio` | 404 |
| 7F.8 | Como gestor: `POST /api/chamados/{CH2}/cancelamento` com `{"motivo": "Aberto por engano"}` | 200; `estado: CANCELADO`, `estadoFinalEm` preenchido; `GET /api/chamados/{CH2}/sla` → `apurado: false` |
| 7F.9 | Repetir o 7F.8 | 422 `CANCELAMENTO_NAO_PERMITIDO` |

## 7G. Enviar evidência ([evidência - 02])

Usa CH em EM_ATENDIMENTO com o atendimento EM_ANDAMENTO de **UT2** (7F.5) e o token de UT2 em `T2`. Antes, crie as imagens (Python com Pillow; a primeira é uma foto "de celular": 400x200, metade vermelha e metade azul, orientação 6 e GPS no EXIF):

```bash
python3 -c "
from PIL import Image
im=Image.new('RGB',(400,200),'red'); im.paste('blue',(200,0,400,200))
e=im.getexif(); e[0x0112]=6
g=e.get_ifd(0x8825); g[1]='S'; g[2]=(3.0,43.0,56.0); g[3]='W'; g[4]=(38.0,31.0,38.0)
im.save('foto-celular.jpg', exif=e)"
printf '%%PDF-1.7\n1 0 obj\n<< >>\nendobj\n' > falso.jpg      # PDF disfarçado de JPEG
head -c 11000000 /dev/zero > grande.jpg                          # 11 MB
```

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7G.1 | Como **UT2**: `curl -i -X POST http://localhost:8080/api/chamados/{CH}/evidencias -H "Authorization: Bearer $T2" -F "arquivo=@foto-celular.jpg;type=image/jpeg"` | 202 `{"id": "...", "estado": "RECEBIDA"}`. Anote o `id` (**EV**) |
| 7G.2 | No psql: `select estado, tipo_conteudo, tamanho, hash_sha256, chave_original from evidencia where id = '{EV}';` | `RECEBIDA`, `image/jpeg`, hash de 64 caracteres, chave `evidencias/{CH}/{EV}/original.jpg` |
| 7G.3 | No console do MinIO (http://localhost:9001, `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` do `.env.dev`), bucket `evidencias`: baixe o objeto e rode `python3 -c "from PIL import Image; i=Image.open('original.jpg'); print(i.size, i.getexif())"`; confira o hash com `sha256sum original.jpg` | Tamanho `(200, 400)` (em pé), EXIF vazio (`{}`), `sha256sum` igual ao `hash_sha256` do 7G.2; a metade vermelha no topo e a azul embaixo |
| 7G.4 | No psql: `select operacao, entidade_tipo, dados from auditoria where entidade_id = '{EV}';` e `select exchange, routing_key, payload from outbox_eventos where payload->>'evidenciaId' = '{EV}';` | Auditoria `ENVIO_EVIDENCIA`/`EVIDENCIA` com tipo, tamanho e hash (sem o nome do arquivo); evento na exchange `evidencias`, routing key `processamento`, payload só com `evidenciaId`, `chamadoId`, `chaveOriginal` e `eventoId` |
| 7G.5 | Repetir o 7G.1 com `-F "arquivo=@falso.jpg;type=image/jpeg"` | 422 `FORMATO_NAO_SUPORTADO` |
| 7G.6 | Repetir o 7G.1 com `-F "arquivo=@grande.jpg;type=image/jpeg"` | 413 `ARQUIVO_MUITO_GRANDE` |
| 7G.7 | Como **UT** (não atribuído): repetir o 7G.1 | 404 |
| 7G.8 | Como gestor: repetir o 7G.1 | 403 |
| 7G.9 | Como gestor: `GET /api/chamados/{CH}` | O atendimento EM_ANDAMENTO traz `evidencias: 1` |
| 7G.10 | Num chamado ATRIBUIDO (ainda sem início do atendimento), como o técnico dele: repetir o 7G.1 | 422 `TRANSICAO_INVALIDA` |

## 7H. Processamento das evidências ([evidência - 03])

Segue o 7G.1 (evidência **EV** enviada; use uma foto grande, por exemplo 4000x3000) com a aplicação e o broker no ar. O publicador roda a cada 5 s.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7H.1 | Em até ~10 s: `select estado, chave_miniatura, chave_visualizacao from evidencia where id = '{EV}';` | `DISPONIVEL` e as chaves `.../miniatura.jpg` e `.../visualizacao.jpg` |
| 7H.2 | No console do MinIO, bucket `evidencias`, pasta de EV: baixe os dois derivados e rode `python3 -c "from PIL import Image; print(Image.open('miniatura.jpg').size, Image.open('visualizacao.jpg').size)"` | Lado maior 320 e 1280, mesma proporção; o `original.jpg` com o mesmo `sha256sum` de antes |
| 7H.3 | No console do RabbitMQ (http://localhost:15672), fila `evidencias.processamento` | Vazia (0 ready, 0 unacked) |
| 7H.4 | `curl -s -H "Authorization: Bearer $TG" http://localhost:8080/actuator/prometheus \| grep '^evidencias'` (token de gestor) | Uma linha por estado, `DISPONIVEL` com a contagem certa |
| 7H.5 | Falha: envie outra foto (**EV2**, 202) e, **logo em seguida**, antes de o publicador rodar, pare o MinIO (`docker stop infra-minio-1`); espere ~25 s, veja o log da aplicação e `select estado from evidencia where id = '{EV2}'`. (Parar o MinIO **antes** de enviar dá 503 `SERVICO_INDISPONIVEL` no envio, e nada chega ao processamento.) | 3 tentativas no log (1 s e 2 s de espera), depois `FALHA`; no console do RabbitMQ a `evidencias.processamento.dlq` tem 1 mensagem a mais (as estatísticas atualizam com alguns segundos de atraso) e a principal está vazia. Religue o MinIO (`docker start infra-minio-1`) e espere uns 10 s: a JVM guarda por esse tempo a falha de DNS de `minio` e responde 503 enquanto isso |
| 7H.6 | `select estado from chamado where id = '{CH}'` e o atendimento | Inalterados pela falha (RN-89) |

## 7I. Listar e baixar evidências ([evidência - 04])

Segue o 7H: EV (DISPONIVEL) no chamado CH, `TG` (gestor), `T2` (UT2, técnico atribuído), `TC` (cliente da empresa do chamado) e `TC2` (cliente de outra empresa). `U=http://localhost:8080`.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7I.1 | `curl -s -H "Authorization: Bearer $TG" $U/api/chamados/{CH}/evidencias` (repetir com `$T2` e `$TC`) | 200; lista por atendimento com `atendimentoId` e `evidencias[]` (id, nome, tipoConteudo, tamanho, hashSha256, estado `DISPONIVEL`, enviadaEm, expurgadaEm nulo) |
| 7I.2 | Repetir o 7I.1 com `$TC2` e com o token de um técnico não atribuído | 404 |
| 7I.3 | `curl -s -D - -o visualizacao.jpg -H "Authorization: Bearer $TG" $U/api/evidencias/{EV}/conteudo` | 200; `Content-Type: image/jpeg`, `Content-Length`, `ETag: "{hash}-visualizacao"`, `Cache-Control: private, no-cache`, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`; lado maior 1280 |
| 7I.4 | Repetir com `?variante=miniatura` (`-o miniatura.jpg`) e `?variante=original` (`-o original.jpg`) | Lado maior 320; o original com `sha256sum` igual ao `hashSha256` da lista |
| 7I.5 | Repetir o 7I.3 com `-H 'If-None-Match: "{hash}-visualizacao"'` | 304, sem corpo |
| 7I.6 | Repetir com `?variante=gigante` | 400 `VALIDACAO` |
| 7I.7 | Evidência ainda `RECEBIDA` (envie outra e baixe antes do publicador rodar, ou use a de falha do 7H.5): `curl -D - -o x.jpg ... /conteudo` | 200 com o original (`ETag` terminando em `-original`) |
| 7I.8 | Com `$TC2`: `curl -i $U/api/evidencias/{EV}/conteudo` | 404 |
| 7I.9 | `update evidencia set estado = 'EXPURGADA', expurgada_em = now() where id = '{EV}';` e repetir o 7I.3 | 410 `EVIDENCIA_EXPURGADA`; a listagem (7I.1) ainda mostra a evidência com `expurgadaEm`. Desfaça o `update` se for usar EV de novo |
| 7I.10 | `curl -i -X DELETE -H "Authorization: Bearer $TG" $U/api/evidencias/{EV}` | 404 (não existe exclusão) |

## 7J. Reprocessar evidência ([evidência - 05])

Usa a evidência **EV2** em `FALHA` do 7H.5 (MinIO já religado).

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7J.1 | Como técnico ou cliente: `curl -i -X POST $U/api/evidencias/{EV2}/reprocessamento -H "Authorization: Bearer $T2"` | 403 |
| 7J.2 | Como gestor: mesmo `POST` com `$TG` | 202 `{"id": "{EV2}", "estado": "RECEBIDA"}` |
| 7J.3 | `select operacao, dados from auditoria where entidade_id = '{EV2}' and operacao = 'REPROCESSAMENTO';` e `select evento_id from outbox_eventos where payload->>'evidenciaId' = '{EV2}';` | Auditoria com `{"de": "FALHA", "para": "RECEBIDA"}`; dois eventos, com `evento_id` diferentes |
| 7J.4 | Em até ~10 s: `select estado from evidencia where id = '{EV2}';` | `DISPONIVEL` |
| 7J.5 | Repetir o 7J.2 | 422 `REPROCESSAMENTO_NAO_PERMITIDO` (já não está em `FALHA`) |
| 7J.6 | `POST` com um id inexistente | 404 |
| 7J.7 | No console do RabbitMQ, mova a mensagem antiga da `evidencias.processamento.dlq` de volta à fila (shovel/move) | Descartada com ack; a evidência segue `DISPONIVEL` e nada é regravado no MinIO |

## 7K. Retenção da outbox e da auditoria

Use um ambiente descartável com dados sintéticos. Configure `app.retencao.cron`
para iniciar um ciclo no horário de teste e acompanhe o log da etapa `outbox`.

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7K.1 | Prepare três linhas: uma publicada há oito dias, outra há seis dias e uma pendente criada há oito dias. Aguarde o ciclo. | Só a publicação de oito dias é removida. |
| 7K.2 | Confira o log e as métricas da etapa `outbox`. | O log e `retencao.itens` mostram um item removido. `retencao.duracao` registra a execução. |
| 7K.3 | Prepare um registro de auditoria com cinco anos e um dia e outro a um dia de completar cinco anos. Aguarde o ciclo. | O registro vencido é removido, o mais recente permanece e a etapa `auditoria` conta uma remoção. |
| 7K.4 | Prepare uma evidência com original e derivados em chamado cancelado há 181 dias e outra em chamado resolvido há um ano. Aguarde o ciclo. | O prefixo da primeira fica vazio no MinIO. A consulta mantém os metadados e o conteúdo responde 410. A auditoria registra `EXPIRACAO_RETENCAO`. A segunda permanece disponível. |
| 7K.5 | Em bucket descartável, prepare órfãos com 25 e 23 horas e um objeto antigo no prefixo de uma evidência em processamento. Aguarde o ciclo. | Só o órfão de 25 horas é removido. A etapa `orfaos` conta a remoção. |

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
- **Falha no banco depois da gravação no storage (objeto órfão), teto de megapixels e as oito orientações do EXIF ([evidência - 02]):** precisam de falha simulada ou de imagens montadas à mão; estão no `RecepcaoEvidenciaTest` e no `SanitizadorImagemTest`.
- **SLA ([sla - 01] a [sla - 05]):** a abertura e a prioridade já acionam os relógios (seção 7D); a reabertura e a apuração ainda não têm operação que as acione e estão nos testes automáticos (`RelogiosSlaTest`, `ApuracaoSlaTest`, `ConsultaChamadoTest`).
- **Transições, histórico de estado e atendimentos ([chamado - 01] e [chamado - 02]):** a atribuição, o início do atendimento e o cancelamento já mudam o estado (seções 7E e 7F); a resolução, a reabertura e o encerramento ainda não existem; estão no `ChamadoModeloTest` e no `AtendimentoChamadoTest`. Na seção 7D o estado é alterado por SQL só para exercitar as consultas.
