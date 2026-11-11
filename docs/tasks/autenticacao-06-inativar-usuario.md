# [autenticação - 06] Inativar usuário

> Requisitos: RF-10, RN-121, RN-132, RN-133, RN-136 · Depende de: [autenticação - 04]. A transferência de chamados vem de [chamado - 13].

## Contexto

Usuários nunca são excluídos, porque auditoria e chamados apontam para eles. Inativar bloqueia o login no Keycloak e marca o registro local. Um técnico com chamados em andamento só pode ser inativado com a indicação de um substituto, para que nenhum chamado fique com técnico inativo. Se a parte local falhar, a conta é reabilitada. Feita na branch `feat/autenticacao-06`, criada a partir de `feat/autenticacao-05`.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /usuarios/{id}/inativacao` (GESTOR) desabilita a conta no Keycloak e marca o usuário como inativo | `POST /api/usuarios/{id}/inativacao`, 204. `InativacaoUsuarioService`: `KeycloakAdminClient.desabilitar` e depois `Usuario.inativar()` numa transação. |
| Técnico com chamados sem substituto é rejeitado com 422 `SUBSTITUTO_OBRIGATORIO`, antes do Keycloak | `ChamadosDoTecnico.contarNaoFinalizados` na validação, antes de `desabilitar`. |
| Rejeitados: substituto inativo, substituto igual ao inativado, gestor inativando a si mesmo, último gestor ativo | 422 `SUBSTITUTO_INVALIDO` (também inexistente ou não técnico), `AUTOINATIVACAO_NAO_PERMITIDA` e `ULTIMO_GESTOR_ATIVO`. |
| Falha simulada na transação reabilita a conta e não transfere nenhum chamado | O `catch` em volta da transação chama `reabilitar` (RN-136). A transferência roda na mesma transação e é desfeita junto. |
| Usuário inativado não consegue logar e recebe 401 com token ainda válido | Conta desabilitada no Keycloak barra o login. O `UsuarioCorrenteConverter`, da autenticação-02, já rejeita token de usuário inativo. |
| A auditoria registra a inativação | `INATIVACAO_USUARIO` na mesma transação, com `perfil` e `substitutoId`. |
| Testes unitários e de integração criados e passando | `InativacaoUsuarioServiceTest` (unitário, 7) e `InativacaoUsuarioTest` (integração, 10, com Keycloak real). |

## Passos técnicos realizados

- [x] **`InativacaoUsuarioService` com validações antes do Keycloak**: 404 → já inativo (204) → autoinativação → último gestor → substituto.
- [x] **Porta `ChamadosDoTecnico`**: em `identidade.api`, com `contarNaoFinalizados` e `transferir`, implementada depois pelo módulo chamado.
- [x] **Fluxo**: validar → desabilitar no Keycloak → transação local (trava, revalidação, inativo, transferência, auditoria) → em falha, reabilitar.
- [x] **`POST /usuarios/{id}/inativacao` com corpo opcional `substitutoId`**: 204, com `@PreAuthorize` e `RegrasAcesso`.
- [x] **Documentado no OpenAPI**: 204/404/422/503 e os códigos na descrição; `docs/openapi.json` regenerado; códigos novos em `docs/erros-api.md`.
- [x] **Também:** `ClienteInativadoListener` inativa os usuários do cliente, como combinado na cadastro-01; `GET /api/tecnicos` passou a devolver `id`; `spring.jpa.open-in-view=false`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Códigos do substituto | `SUBSTITUTO_OBRIGATORIO` e `SUBSTITUTO_INVALIDO` | Decisão do usuário: um código por motivo principal; a mensagem é a mesma para os casos de substituto inválido. |
| Usuário já inativo | 204 idempotente, sem Keycloak nem auditoria | Igual à inativação de cliente. |
| `substitutoId` | É o `id` local, como no caminho | Por isso `GET /api/tecnicos` passou a devolver `id`. |
| Substituto informado sem necessidade | Validado do mesmo jeito; só transfere se o inativado for técnico | Um id errado não passa em silêncio. |
| Módulo chamado ainda não existe | `Optional<ChamadosDoTecnico>`: sem implementação, a contagem é 0 | A porta passa a valer sozinha quando a [chamado - 13] publicar o bean. Não existe chamado até lá, logo não há o que bloquear. |
| Corrida entre inativações | Trava nas linhas dos gestores ativos e revalidação na transação | Dois gestores inativando um ao outro, ou dois técnicos substitutos um do outro, passariam juntos pela validação inicial. Trava global basta: inativação é rara. |
| `spring.jpa.open-in-view=false` | Desligado para a aplicação toda | Com ele ligado, a sessão JPA durava a requisição e a revalidação lia o objeto em cache. Provado em teste: os dois técnicos ficavam inativos. O projeto não tem relações lazy. |
| Compensação | Só reabilita se o registro continua ativo | A falha pode vir de uma inativação concorrente que já valeu. Se a reabilitação falhar, só registra o erro. |
| Transação `REQUIRES_NEW` | Em toda inativação | O ouvinte de `ClienteInativado` roda depois do commit; com `REQUIRED`, entraria na transação encerrada e nada gravaria. |
| Usuários do cliente inativado | `@TransactionalEventListener`, depois do commit, com `Ator.SISTEMA` | Falha em um usuário vai para o log: não desfaz o cliente nem interrompe os demais. |
| Dados da auditoria | Só `perfil` e `substitutoId` | Sem dado pessoal na trilha (RN-122). |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração ou variável nova.
- `spring.jpa.open-in-view=false` vale para a aplicação toda: acesso lazy fora de transação daria erro, se alguma entidade futura usar.
- O contrato mudou: `POST /api/usuarios/{id}/inativacao` e o campo `id` em `GET /api/tecnicos`. O `docs/openapi.json` está atualizado.
- A [chamado - 13] implementa `ChamadosDoTecnico`, sem mudança no identidade. Ela audita `TRANSFERENCIA_POR_INATIVACAO`.
- A inativação de um cliente agora chama o Keycloak para cada usuário, na mesma requisição, depois do commit.

## Como verificar

Ver `docs/testes/autenticacao-06-inativar-usuario.md`.
