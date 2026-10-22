# [plataforma - 05] Padronizar o tratamento de erros

> Requisitos: RN-13 (transição inválida é erro de negócio), RN-117 (conflito explícito no bloqueio otimista) · Depende de: [plataforma - 01]

## Contexto

O frontend precisa receber erros num formato único e com códigos estáveis para mostrar a mensagem certa. Erros de negócio (como transição de estado inválida) precisam ser diferenciados de erros de validação e de conflito de concorrência. Antes desta task cada erro saía no formato padrão do Spring, sem código, e os 401/403 dos filtros de segurança nem passavam pelo MVC.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Todo erro sai como Problem Details (RFC 9457) com campo `codigo` | `shared.internal.GlobalExceptionHandler` (`@RestControllerAdvice`) estende `ResponseEntityExceptionHandler`, que já gera `ProblemDetail` para os erros do Spring MVC; o override de `handleExceptionInternal` acrescenta o `codigo`. Os handlers próprios montam o `ProblemDetail` com `codigo`. Um handler de `Exception` garante o 500 no mesmo formato. |
| 400 / 401 / 403 / 404 / 409 / 422 | 400 `VALIDACAO` (com lista `erros` por campo), 401 `NAO_AUTENTICADO`, 403 `ACESSO_NEGADO`, 404 `RECURSO_NAO_ENCONTRADO`, 409 `CONFLITO_VERSAO`, 422 com o código da regra. Tabela completa em `docs/erros-api.md`. |
| Recurso de outro cliente retorna 404, nunca 403 | `shared.api.RecursoNaoEncontradoException` serve aos dois casos (inexistente e fora do isolamento) com a mesma mensagem fixa ("Recurso não encontrado."), sem id nem nome do dono. Uso documentado em `docs/erros-api.md`. |
| Conflito de bloqueio otimista retorna 409 com mensagem clara | `OptimisticLockingFailureException` (pai de `ObjectOptimisticLockingFailureException`) → 409 "O registro foi alterado por outra operação. Recarregue os dados e tente novamente." |
| Testes unitários criados e passando | `GlobalExceptionHandlerTest` (10 testes, MockMvc standalone, sem subir contexto) + teste de integração do 401 vindo do filtro. |

## Passos técnicos realizados

- [x] **Criar `RegraNegocioException(codigo, mensagem)` no shared**
  `shared.api.RegraNegocioException(CodigoErro, String)` → 422. Junto, `shared.api.RecursoNaoEncontradoException` → 404, necessária para o critério do isolamento.

- [x] **Criar `GlobalExceptionHandler` com `@RestControllerAdvice`**
  `shared.internal.GlobalExceptionHandler`. Para levar os 401/403 dos filtros de segurança ao mesmo handler, `shared.internal.SegurancaErrosConfig` publica um `Customizer<HttpSecurity>`: o Spring Security 7 o aplica a toda cadeia de filtros, e o entry point / access denied handler repassam a exceção ao `HandlerExceptionResolver`. Assim o `shared` não precisa definir a cadeia de segurança (que fica para a task de autenticação).

- [x] **Mapear `ObjectOptimisticLockingFailureException` para 409**

- [x] **Criar o catálogo inicial de códigos**
  Enum `shared.api.CodigoErro`: códigos gerais (`VALIDACAO`, `NAO_AUTENTICADO`, `ACESSO_NEGADO`, `RECURSO_NAO_ENCONTRADO`, `CONFLITO_VERSAO`, `REQUISICAO_INVALIDA`, `ERRO_INTERNO`) e de negócio (`TRANSICAO_INVALIDA`, `CONTRATO_FORA_VIGENCIA`, `CONTRATO_SEM_POLITICA`, `ORDEM_PRAZOS_INVALIDA`, `EVIDENCIA_OBRIGATORIA`, `CANCELAMENTO_NAO_PERMITIDO`, `REATRIBUICAO_NAO_PERMITIDA`, `REABERTURA_NAO_PERMITIDA`), cada um com a RN de origem.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Tipo do código | Enum `CodigoErro` em `shared.api` | Catálogo único e verificado pelo compilador; nenhum módulo inventa string solta. |
| Status da regra de negócio | Sempre 422, fixo na exceção | O código diferencia a regra; o status só diferencia a categoria. |
| Exceção mapeada para 409 | `OptimisticLockingFailureException` (pai) | Cobre `ObjectOptimisticLockingFailureException` e qualquer outra falha de bloqueio otimista traduzida pelo Spring. |
| Erros de segurança | `Customizer<HttpSecurity>` + `HandlerExceptionResolver` | Um único lugar formata todos os erros; não cria `SecurityFilterChain` antes da task de autenticação. |
| `GlobalExceptionHandler` público | Classe `public` em `shared.internal` | O teste unitário fica em `unit/` (é o que o `make unit-test` roda); o Modulith continua impedindo outros módulos de importar `internal`. |
| Mensagem do 500 | Fixa; exceção real só no log | Não vazar detalhe interno ao cliente. |
| `title` | O padrão do Spring (em inglês) | O frontend usa `codigo`; o `detail` das exceções próprias é em português. |

## Atenção ao atualizar um ambiente existente

Nada a fazer: sem variável nova nem migração. A partir daqui, toda resposta de erro (inclusive o 401 sem token) é `application/problem+json`.

## Como verificar

Ver `docs/testes/plataforma-05-padronizar-tratamento-erros.md`.
