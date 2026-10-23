# Erros da API

Qualquer erro sai como **Problem Details** (RFC 9457, `Content-Type: application/problem+json`) com o campo extra `codigo`. O frontend escolhe a mensagem pelo `codigo`, não pelo texto: **um código publicado nunca muda de nome**; se a regra mudar, cria-se outro.

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Chamado ENCERRADO não aceita transição.",
  "instance": "/chamados/0199.../estado",
  "codigo": "TRANSICAO_INVALIDA"
}
```

Erro de validação traz também `erros`: `[{ "campo": "titulo", "mensagem": "must not be blank" }]`.

## Status × código

| Situação | Status | `codigo` | Como gerar |
| --- | --- | --- | --- |
| Campo inválido (`@Valid`), JSON malformado, parâmetro com tipo errado | 400 | `VALIDACAO` | Automático |
| Sem token / token inválido | 401 | `NAO_AUTENTICADO` | Automático (filtro de segurança) |
| Autenticado, mas sem o papel exigido | 403 | `ACESSO_NEGADO` | Automático (filtro ou `@PreAuthorize`) |
| Recurso inexistente **ou de outro cliente** | 404 | `RECURSO_NAO_ENCONTRADO` | `throw new RecursoNaoEncontradoException()` |
| Bloqueio otimista: registro alterado por outra operação (RN-117) | 409 | `CONFLITO_VERSAO` | Automático (`ObjectOptimisticLockingFailureException`) |
| Regra de negócio violada | 422 | ver catálogo | `throw new RegraNegocioException(CodigoErro.X, "mensagem")` |
| Outros erros do Spring MVC (405, 406, 415…) | o do Spring | `REQUISICAO_INVALIDA` | Automático |
| Erro inesperado | 500 | `ERRO_INTERNO` | Automático; a mensagem real só vai para o log |

**Isolamento (RN-126):** recurso de outro cliente retorna **404, nunca 403**, para não revelar que existe. O serviço busca já filtrando pelo cliente do token e, se não achar, lança `RecursoNaoEncontradoException`, a mesma de quando o id não existe.

## Catálogo de regras de negócio (422)

Fica em `shared.api.CodigoErro`. Novo código entra lá, com a RN em comentário, e nesta tabela.

| Código | Regra |
| --- | --- |
| `TRANSICAO_INVALIDA` | Transição fora da máquina de estados ou a partir de estado final (RN-11, RN-13) |
| `CONTRATO_FORA_VIGENCIA` | Chamado aberto sob contrato sem vigência ativa (RN-01) |
| `CONTRATO_SEM_POLITICA` | Contrato sem política de SLA vinculada (RN-03) |
| `ORDEM_PRAZOS_INVALIDA` | Política com prazos fora da ordem resposta < campo < resolução (RN-30, RN-32) |
| `EVIDENCIA_OBRIGATORIA` | Conclusão de atendimento sem evidência (RN-72) |
| `CANCELAMENTO_NAO_PERMITIDO` | Cancelamento a partir de `RESOLVIDO` ou depois (RN-19) |
| `REATRIBUICAO_NAO_PERMITIDA` | Reatribuição fora de `ATRIBUIDO`/`EM_ATENDIMENTO` (RN-20) |
| `REABERTURA_NAO_PERMITIDA` | Reabertura fora de `RESOLVIDO` (RN-93, RN-99) |
