# [autenticação - 05] Consultar usuários, técnicos e o perfil corrente

> Requisitos: RN-130; apoio a RF-10 e RF-17 · Depende de: [autenticação - 04]

## Contexto

O frontend precisa saber quem está logado para montar o menu, e o gestor precisa listar usuários e escolher técnicos na atribuição e na inativação. A lista de gestores ativos também é usada para notificar violações de SLA. Essas consultas leem a cópia local do perfil, que serve só para consulta, nunca para autorizar. Feita na branch `feat/autenticacao-05`, criada a partir de `feat/autenticacao-04-plataforma-06`.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `GET /me` retorna sub, nome, perfil e, para cliente, a empresa | Já existia como `GET /api/usuarios/me` (autenticação-01 e 04), com `clienteId`. Ganhou `ativo`, por usar a mesma resposta da listagem, e teste do perfil cliente. |
| `GET /usuarios` (GESTOR) filtra por perfil, ativo e empresa, com paginação | `GET /api/usuarios?perfil=&ativo=&clienteId=&page=&size=&sort=`: `UsuarioConsultaService.listar` monta os filtros com `Specification`, como `ClienteService.listar`. 20 por página, máximo 100, ordem por `nome`. Inclui inativos. |
| `GET /tecnicos?ativos=true` (GESTOR) retorna cada técnico ativo com a quantidade de chamados não finalizados | `GET /api/tecnicos`, paginado como `/api/usuarios`, responde `sub`, `nome` e `chamadosNaoFinalizados`. Reaproveita `listar` com perfil `TECNICO`. A carga é `0` até o módulo chamado existir. |
| Técnico e cliente recebem 403 em `/usuarios` e `/tecnicos` | `RegrasAcesso` do identidade passou a cobrir `/api/usuarios` e `/api/tecnicos`, com `@PreAuthorize` nos controllers. |
| Testes de integração criados e passando | `ConsultaUsuarioTest` (7) e as rotas novas em `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **`GET /me`**: já existia; ver a primeira linha da tabela acima.
- [x] **`GET /usuarios` com filtros e paginação**: em `UsuarioController`, resposta `PagedModel<UsuarioResponse>`.
- [x] **`GET /tecnicos`**: novo `TecnicoController`, com carga fixa em 0 até o módulo chamado existir.
- [x] **`listarGestoresAtivos()` na API pública**: nova interface `identidade.api.UsuarioConsulta`, no padrão de `ClienteConsulta`, implementada por `UsuarioConsultaService`.
- [x] **Documentado no OpenAPI**: tag Identidade, `@Operation` nos dois endpoints; `docs/openapi.json` regenerado.
- [x] **Também:** corrigido o `KeycloakAdminAdapter.traduzir`: timeout durante a leitura do corpo agora é 503 com retry, não 500 (RN-137). Era a causa do teste intermitente; ver a obs. 1 em `docs/testes`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Rota do perfil | Mantida em `/api/usuarios/me` | Já está no contrato, nos testes e no plano de testes manuais. |
| Empresa no `/me` | Só `clienteId` | Atende ao critério sem novo método em `ClienteConsulta`. |
| `/api/tecnicos` paginado | Mesmo `PagedModel` de `/api/usuarios` | Decisão do usuário: mantém o padrão das listagens. O seletor do frontend precisa paginar ou pedir `size` maior (máximo 100). |
| `ativos` omitido | Assume `true`; `ativos=false` lista os inativos | O uso principal é escolher técnico para atribuir. |
| Filtro de empresa | Parâmetro `clienteId` | Mesmo nome do campo da resposta. |
| Retorno de `listarGestoresAtivos()` | `List<UUID>` com os subs | O `sub` identifica o usuário no sistema (RN-129), e o gestor é avisado pelo sino (RN-39). |
| Resposta de técnico | `sub`, `nome`, `chamadosNaoFinalizados` | Só o necessário para escolher o técnico. |
| Regra de rota sem método | `requestMatchers("/api/usuarios", "/api/tecnicos")` | Vale para `POST` e `GET`. O caminho é exato: `/api/usuarios/me` continua aberto a todos. |
| Falha de leitura do corpo do Keycloak | `IOException` na origem → `IdpIndisponivelException` | Resposta interrompida é lentidão, não configuração. JSON inválido com resposta completa continua 500, sem retry. |
| Índices | Nenhum novo | O volume de usuários é pequeno; entra quando a consulta justificar. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração ou variável nova.
- O contrato mudou: `GET /api/usuarios`, `GET /api/tecnicos` e o campo `ativo` em `GET /api/usuarios/me`. O `docs/openapi.json` está atualizado.
- Quando o módulo chamado existir, a carga real substitui o `0` fixo de `TecnicoController`.

## Como verificar

Ver `docs/testes/autenticacao/autenticacao-05-consultar-usuarios-tecnicos-perfil.md`.
