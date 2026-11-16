# [cadastro - 02] Cadastrar contratos

> Requisitos: RF-06, RN-01, RN-06 · Depende de: [cadastro - 01]

## Contexto

Todo chamado é aberto sob um contrato. O contrato tem vigência e só aceita chamados dentro dela. Um cliente pode ter vários contratos ao mesmo tempo.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Gestor cria e lista contratos com filtro por cliente e vigência | `POST /api/contratos` e `GET /api/contratos?clienteId=&vigente=&page=&size=&sort=`, com `@PreAuthorize("hasRole('GESTOR')")` e regra em `CadastroRegrasAcesso`. A listagem é paginada (`PagedModel`) e ordenada por código por padrão. O filtro `vigente` compara com a data de hoje. |
| Fim de vigência anterior ao início é rejeitado | 400 `VALIDACAO` no campo `fimVigencia`, via `@AssertTrue` em `DadosContrato`. No banco, o CHECK `contrato_vigencia_ck`. |
| O contrato vale até 23:59:59 do último dia, no fuso do sistema | `Contrato.estaVigenteEm(LocalDate)` tem início e fim inclusivos. A data de hoje vem de `LocalDate.now(clock)`, e o `Clock` está no fuso do sistema (`FUSO_HORARIO`). A listagem aplica a mesma regra em SQL, com `inicio <= hoje <= fim`. |
| O cliente vê apenas os contratos vigentes da própria empresa (`GET /me/contratos`) | `GET /api/me/contratos` (CLIENTE), paginado. A empresa vem do usuário autenticado (`UsuarioCorrente.empresaClienteId`), nunca de parâmetro (RN-126). |
| Testes unitários e de integração criados e passando | `ContratoVigenciaTest` (unitário, 5) e `ContratoTest` (integração, 9), mais as rotas novas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Migração da tabela `contrato`** (`V7__cria_contrato.sql`) com:
  - FK para `cliente`;
  - `codigo` UNIQUE;
  - `inicio_vigencia` e `fim_vigencia` em `date`;
  - CHECK do fim maior ou igual ao início;
  - índice em `cliente_id`.
  
  A coluna da política fica para a [cadastro - 04]: a tabela `politica_sla` ainda não existe aqui.
- [x] **Método de domínio `estaVigenteEm(LocalDate)`** em `Contrato`.
- [x] **`POST /contratos` e `GET /contratos` (GESTOR)**, com o prefixo `/api`.
- [x] **`GET /me/contratos` (CLIENTE)**, filtrando pela empresa do usuário corrente: `/api/me/contratos`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Código do contrato | Texto livre informado pelo gestor (até 50 caracteres), único no sistema. Repetido responde 409 `CODIGO_CONTRATO_JA_EXISTE` | Decisão do usuário. Um código basta para identificar o contrato em qualquer tela. |
| Filtro de vigência | `?vigente=true\|false`, em relação a hoje | Decisão do usuário. É o que o frontend precisa. |
| Fim antes do início | 400 `VALIDACAO` no campo `fimVigencia` | Decisão do usuário. É validação do corpo, sem código novo. |
| `/me/contratos` | Paginado (`PagedModel`) | Decisão do usuário. Segue o padrão de listagem do README. |
| Cliente inexistente / inativo | 422 `CLIENTE_INEXISTENTE` / `CLIENTE_INATIVO` | Regra prometida no cadastro-01. Os códigos já existiam (RF-09). |
| Empresa no `/me/contratos` | `@AuthenticationPrincipal(expression = "empresaClienteId")` | O `identidade` já depende do `cadastro.api`. Importar `UsuarioCorrente` no cadastro forma um ciclo, que o `ModularidadeTest` reprova (verificado). |
| Usuário CLIENTE sem empresa | Lista vazia | Não acontece pelo cadastro de usuário (RF-09). Se acontecer, não vaza contrato de ninguém. |
| Edição e remoção de contrato | Não criadas | Não pedidas. |
| `Contrato` e `DadosContrato` públicos | Públicos dentro de `internal` | O teste unitário fica em `unit/`, como o `DadosCliente`. O Modulith continua barrando outros módulos. |

## Atenção ao atualizar um ambiente existente

- A `V7` roda sozinha na próxima subida. Nenhuma variável nova.
- Código de erro novo: `CODIGO_CONTRATO_JA_EXISTE` (409).

## Como verificar

Ver `docs/testes/cadastro/cadastro-02-cadastrar-contratos.md` e a seção 4A do `docs/plano-testes-manuais.md`.
