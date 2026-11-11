# [autenticação - 04] Cadastrar usuário

> Requisitos: RF-09, RN-121, RN-127, RN-128, RN-129, RN-130, RN-136 · Depende de: [autenticação - 03], [auditoria - 01], [cadastro - 01]

## Contexto

O gestor cadastra clientes, técnicos e outros gestores. A conta vive no Keycloak e o vínculo de negócio (`sub`, nome, perfil e, para cliente, a empresa) vive no app-principal. Se a gravação no banco falhar depois de criar a conta, a conta precisa ser removida, para não sobrar conta sem vínculo. A senha nunca passa pelo sistema. Feita na branch `feat/autenticacao-04-plataforma-06`, junto com a [plataforma - 06].

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /usuarios` (somente GESTOR) cria a conta no Keycloak com o papel certo e o registro local | `POST /api/usuarios` com `@PreAuthorize("hasRole('GESTOR')")`. O `CadastroUsuarioService` cria a conta (`KeycloakAdminClient.criarUsuario`, username = e-mail), atribui o papel e grava o `usuario` local com o `sub` devolvido. |
| O usuário criado recebe no Mailpit o e-mail para definir a senha | `enviarDefinicaoSenha` **depois do commit** da transação local. Se só o e-mail falhar, o cadastro vale e a resposta traz `emailDefinicaoSenhaEnviado: false`. |
| Usuário cliente sem empresa, ou com empresa inativa, é rejeitado com 422 | Antes de chamar o Keycloak: 422 `CLIENTE_OBRIGATORIO`, `CLIENTE_INEXISTENTE` ou `CLIENTE_INATIVO` (via `ClienteConsulta`). Técnico ou gestor com empresa → 422 `CLIENTE_NAO_PERMITIDO`. |
| E-mail já cadastrado retorna 409 | Checado na tabela `usuario` sem diferenciar maiúsculas, antes do Keycloak → 409 `USUARIO_JA_EXISTE`. O índice único em `lower(email)` (V3) barra a corrida: `DataIntegrityViolationException` também vira 409, depois da compensação. |
| Falha simulada no banco remove a conta recém-criada no Keycloak (compensação) | O `catch` em volta da atribuição do papel e da transação local chama `KeycloakAdminClient.remover(sub)` (RN-136), e a exceção original segue. |
| A auditoria registra o cadastro com o sub do gestor | Na mesma transação do registro local: `CADASTRO_USUARIO`, ator = gestor do token, entidade = o usuário criado, `dados` com perfil e `clienteId`. |
| Nenhum campo de senha existe no request ou no banco | `NovoUsuario` tem só nome, e-mail, perfil e `clienteId`; a tabela `usuario` não tem coluna de senha. Os dois são conferidos em teste. |
| Testes unitários e de integração criados e passando | `NovoUsuarioValidacaoTest` (unitário, 3) e `CadastroUsuarioTest` (integração, 9, com Keycloak real e Mailpit). |

## Passos técnicos realizados

- [x] **Migração da tabela `usuario`**: já existia. `V2__cria_usuario.sql` (autenticação-01) e `V3__adiciona_email_cliente_version_usuario.sql` (autenticação-03); a FK `usuario.cliente_id → cliente` veio na `V5` (cadastro-01). Nenhuma migração nova.
- [x] **Entidade `Usuario` e repositório**: já existiam. Ganharam o construtor de cadastro e `existsByEmailIgnoreCase`.
- [x] **DTO de entrada com Bean Validation**: `NovoUsuario` (nome, e-mail, perfil obrigatórios; `clienteId` opcional).
- [x] **`CadastroUsuarioService`**: Keycloak (conta + papel) → `TransactionTemplate` (registro local + auditoria) → e-mail de senha depois do commit. Sem `@Transactional` no método, para a chamada externa não segurar a transação do banco.
- [x] **Compensação no `catch`**: remove a conta só se nenhum registro local a usa (ver Decisões). Se a remoção falhar, só registra o erro, e a conta órfã é reaproveitada no próximo cadastro do mesmo e-mail (autenticação-03).
- [x] **`POST /api/usuarios` com `@PreAuthorize("hasRole('GESTOR')")`**: 201 com `Location`.
- [x] **Documentado no OpenAPI**: tag Identidade, 201/400/401/403/409/422/500/503, exemplos no `NovoUsuario`; `docs/openapi.json` regenerado.
- [x] **Também:** `UsuarioCorrente.empresaClienteId` passou a vir do registro local, combinado na autenticação-02 para esta task; `GET /api/usuarios/me` passou a devolver `email` e `clienteId`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Username no Keycloak | O e-mail, em minúsculas | Um identificador só: o login é o e-mail, e a conta órfã (busca por username) é a do mesmo e-mail que o 409 protege. |
| Ordem das validações | 400 (campos) → 422 (empresa) → 409 (e-mail local) → Keycloak | Nada é criado no Keycloak quando o pedido já é inválido. |
| E-mail falha depois do commit | 201 com `emailDefinicaoSenhaEnviado: false` e log de alerta | O cadastro foi feito; um 503 levaria o gestor a repetir e receber 409. Reenvio fica para um endpoint futuro. |
| Dados da auditoria | Só `perfil` e `clienteId` | A trilha é imutável por 5 anos; nome e e-mail (dado pessoal) ficam só na tabela `usuario`. |
| Guarda na compensação | Só remove a conta se não houver `usuario` com aquele `sub` | Dois envios simultâneos do mesmo e-mail com conta órfã: o segundo falha no `sub` único e não pode apagar a conta que o primeiro acabou de cadastrar. |
| Falha ao atribuir o papel | Também compensa (remove a conta) | Conta sem papel não deve sobrar. |
| Prefixo | `/api/usuarios` | Igual aos demais endpoints. |
| `DataIntegrityViolation` na gravação local | 409 depois da compensação | É a corrida de e-mail ou `sub` repetido que a checagem prévia não pegou. |
| Papel exigido antes da validação (D1 do teste manual) | Cada módulo publica um `shared.api.RegrasAcesso` com as rotas e o papel (`POST /api/usuarios` no identidade, `/api/clientes/**` no cadastro), aplicado na `SecurityFilterChain`; o `@PreAuthorize` continua no método | O `@PreAuthorize` roda depois que o Spring lê e valida o `@Valid @RequestBody`: técnico ou cliente com corpo inválido recebia 400 com a lista de campos, em vez de 403. A regra na cadeia barra antes de ler o corpo. Por módulo, para o identidade não conhecer as rotas dos outros. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração ou variável nova.
- O contrato mudou (`POST /api/usuarios`; `GET /api/usuarios/me` com `email` e `clienteId`): o `docs/openapi.json` está atualizado neste PR.
- E-mail de senha no dev chega no Mailpit (http://localhost:8025); o link leva ao Keycloak para definir a senha.

## Como verificar

Ver `docs/testes/autenticacao/autenticacao-04-cadastrar-usuario.md`.
