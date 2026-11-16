# Testes — [cadastro - 01] Cadastrar clientes

> Executados em 03/11/2026, na branch `feat/cadastro` (criada a partir de `feat/auditoria`; alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers (`pgvector/pgvector:pg17`). Verificação manual no ambiente dev, com token real do gestor emitido pelo Keycloak.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 81 testes, 0 falhas (`ClienteTest` 9, `DadosClienteValidacaoTest` 4, `GlobalExceptionHandlerTest` 13 e os anteriores) | ✅ |
| 2 | Validação do cadastro (unitário) | `DadosClienteValidacaoTest`: completo, e-mail inválido, razão social e endereço ausentes, UF e CEP fora do formato | só os campos errados apontados | passou | ✅ |
| 3 | Criar | `ClienteTest.shouldCreateActiveClient` | 201, `Location`, `ativo: true`, `version: 0` | passou | ✅ |
| 4 | E-mail inválido | `shouldReturn400ForInvalidContactEmail` | 400 `VALIDACAO`, `erros[0].campo = emailContato` | passou | ✅ |
| 5 | Só gestor | `shouldAllowOnlyManager` (técnico ativo com token real) | 403 no `POST` e no `GET` | passou | ✅ |
| 6 | Listagem paginada com filtros | `shouldListPaginatedFilteringByNameAndActive` (3 clientes, um inativo; busca em maiúsculas; `size=2`) | 2 itens na página, total 3, 2 páginas; `ativo=false` só o inativo; padrão de 20 por página | passou | ✅ |
| 7 | Edição concorrente | `shouldEditWithCurrentVersionAndRejectStaleVersion` | `version` certa → 200, `version: 1`; `version` antiga → 409 `CONFLITO_VERSAO` | passou | ✅ |
| 8 | Inativação | `shouldInactivateKeepingClientAndPublishEventOnce` (inativa duas vezes) | 204 nas duas; `estaAtivo` false; e-mail de contato ainda consultável; 1 evento `ClienteInativado`; continua na listagem | passou | ✅ |
| 9 | Cliente inexistente | `shouldReturn404ForUnknownClient` | 404 | passou | ✅ |
| 10 | FK do usuário | `shouldRejectUserLinkedToUnknownClient` | violação de `usuario_cliente_fk` | passou | ✅ |
| 11 | `?sort=` inexistente | `ClienteTest.shouldReturn400ForUnknownSortProperty` e `GlobalExceptionHandlerTest.shouldReturn400ForUnknownSortProperty` | 400 `VALIDACAO` | passou (após correção, ver obs. 1) | ✅ |
| 12 | Dev: fluxo com token real | Login PKCE do gestor no Keycloak; `POST`, `POST` com e-mail inválido, `PUT`, inativação, `GET ?ativo=false&razaoSocial=e2e`, `?sort=senha`, sem token | 201; 400 `VALIDACAO`; 200; 204; o inativo listado; 400; 401 | todos conforme o esperado | ✅ |
| 13 | Dev: dois gestores editando | Gestor A salva com `version 1` (→ 2); gestor B salva com a `version 1` que tinha visto | B recebe 409 | A: `version 2`; B: 409 `CONFLITO_VERSAO` | ✅ |
| 14 | Dev: migração | Backend de dev reiniciado pelo devtools com os arquivos finais | V5 aplicada, aplicação no ar | `now at version v5`, health 200 | ✅ |

O cliente criado no item 12 foi apagado do banco de dev depois do teste, por SQL. A API não tem remoção.

## Observações

1. **Achado:** `?sort=` com campo inexistente dava **500** (`PropertyReferenceException` do Spring Data, que caía no handler genérico). Agora é 400 `VALIDACAO`, no `GlobalExceptionHandler`, para todas as listagens.
2. **Achado (dev):** a página saía com 10 itens: o padrão do `@PageableDefault` é 10, e não os 20 combinados. Corrigido com `size = 20` e coberto no item 6.
3. **Comportamento:** um `PUT` que reenvia os mesmos dados não incrementa a `version` (o Hibernate não vê alteração), então um segundo `PUT` com a mesma `version` também passa. Não há sobrescrita, porque nada mudou. Com dados diferentes, o 409 acontece (item 13).
4. **Parcial:** "cliente inativo não aceita novo contrato nem novo usuário" está coberto só pela consulta `estaAtivo` (item 8). A recusa com 422 entra na [cadastro - 02] e na [autenticação - 04].
5. **Não verificado:** escuta do evento `ClienteInativado` por outro módulo; o ouvinte nasce na [autenticação - 06].
