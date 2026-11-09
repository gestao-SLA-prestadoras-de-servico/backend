# [cadastro - 01] Cadastrar clientes

> Requisitos: RF-05, RN-06, RN-07, RN-08 · Depende de: [plataforma - 03], [autenticação - 02]

## Contexto

O cliente é a empresa contratante. O e-mail de contato do cadastro é o destino de todos os e-mails daquele cliente. Inativar um cliente não pode apagar seu histórico de chamados nem de contratos. É pré-requisito da [autenticação - 04], que vincula o usuário de perfil cliente à empresa.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Gestor cria, edita, lista (paginado, com filtro por nome e ativo) e inativa clientes | `POST /api/clientes`, `PUT /api/clientes/{id}`, `GET /api/clientes?razaoSocial=&ativo=&page=&size=&sort=` e `POST /api/clientes/{id}/inativacao`, todos com `@PreAuthorize("hasRole('GESTOR')")`. A listagem sai no formato `PagedModel` do Spring Data e filtra a razão social por "contém", sem diferenciar maiúsculas. |
| E-mail de contato inválido retorna 400 | `@Email` em `DadosCliente` → 400 `VALIDACAO`, com o campo em `erros`. Endereço também validado: UF com 2 letras maiúsculas, CEP com 8 dígitos. |
| Cliente inativo não aceita novo contrato nem novo usuário | `cadastro.api.ClienteConsulta.estaAtivo(id)` é a consulta que o cadastro de contrato ([cadastro - 02]) e o de usuário ([autenticação - 04]) usam para barrar. **O bloqueio em si fica nessas tasks**, porque os endpoints ainda não existem. |
| Cliente inativo continua aparecendo nos relatórios e no histórico | Cliente nunca é apagado: não há endpoint de remoção, e a FK `usuario.cliente_id → cliente` não tem cascata. A listagem mostra os inativos (filtro `ativo=false`), e `ClienteConsulta` continua respondendo por eles (e-mail de contato incluído). |
| Testes unitários e de integração criados e passando | `DadosClienteValidacaoTest` (unitário, 4) e `ClienteTest` (integração, 9), mais um caso novo no `GlobalExceptionHandlerTest`. |

## Passos técnicos realizados

- [x] **Migração da tabela `cliente`** (`V5__cria_cliente.sql`): razão social, e-mail de contato, endereço em campos (logradouro, número, complemento, bairro, cidade, UF, CEP, com CHECK de formato de UF e CEP), `ativo` e `version`. Junto, a FK `usuario_cliente_fk` na coluna `usuario.cliente_id`, criada na V3.
- [x] **Entidade, repositório e serviço no módulo cadastro**: `Cliente` (com `Endereco` `@Embeddable`), `ClienteRepository` (filtros por `Specification`) e `ClienteService`.
- [x] **`POST`, `PUT`, `GET /clientes` e `POST /clientes/{id}/inativacao` (GESTOR)**: com o prefixo `/api`, como `/api/usuarios/me`.
- [x] **Consulta do e-mail de contato na API do módulo**: `ClienteConsulta.emailContato(id)`; junto, `estaAtivo(id)` e o evento `ClienteInativado`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Efeito de inativar (decisão pendente da task) | Marca o cliente como inativo e publica `ClienteInativado`. Contrato, usuário e chamado consultam `estaAtivo`. A inativação dos usuários do cliente escuta o evento na [autenticação - 06] | A inativação de usuário (Keycloak + registro local) é da autenticação-06. Chamar o `identidade` daqui formaria um ciclo, porque o identidade vai consultar o cadastro. |
| Inativar de novo | 204, sem segundo evento | Idempotente: quem repete a ação não dispara efeitos de novo. |
| Editar cliente inativo | Permitido | Corrigir o e-mail ou o endereço de um cliente do histórico é legítimo. |
| Auditoria | Não audita cliente | Não está na RN-121. |
| Endereço | Campos separados, validados | Permite relatório por cidade/UF e evita endereço incompleto. |
| E-mail de contato repetido | Permitido | Empresas do mesmo grupo podem ter o mesmo contato. |
| Edição concorrente | O `PUT` exige a `version` vista pelo gestor; diferente da atual → 409 `CONFLITO_VERSAO` (RN-117) | Sem isso, o segundo gestor sobrescreveria calado a edição do primeiro. Um `PUT` que não muda nada não incrementa a `version`. |
| Formato do `PUT` | Os campos do `POST` mais `version`, no mesmo nível | Um corpo só para o frontend montar. |
| Paginação | `PagedModel` do Spring Data, padrão de 20 itens, máximo de 100, ordenação padrão por razão social | Formato estável do Spring Data 4; vira o padrão das listagens (README). |
| `?sort=` inexistente | 400 `VALIDACAO` no `GlobalExceptionHandler` | Antes dava 500 (`PropertyReferenceException`). Vale para toda listagem. |
| `GET /clientes/{id}` | Não criado | Decisão desta task: a tela de edição usa o item da listagem, que já traz a `version`. |
| `DadosCliente` e `Endereco` públicos | Públicos dentro de `internal` | O teste unitário fica em `unit/` (o que o `make unit-test` roda), como o `GlobalExceptionHandler` na plataforma-05. O Modulith continua impedindo outros módulos de importar. |

## Atenção ao atualizar um ambiente existente

- A `V5` roda sozinha na próxima subida do backend. Se o banco tiver linha em `usuario` com `cliente_id` preenchido à mão (sem cliente correspondente), a FK falha; limpe o `cliente_id` dessa linha.
- Nenhuma variável nova.
- Para a [cadastro - 02] e a [autenticação - 04]: rejeitar com 422 quando `ClienteConsulta.estaAtivo(clienteId)` for `false`. Para a [autenticação - 06]: `@TransactionalEventListener` de `ClienteInativado`.

## Como verificar

Ver `docs/testes/cadastro/cadastro-01-cadastrar-clientes.md`.
