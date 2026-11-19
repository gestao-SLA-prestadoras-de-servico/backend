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
| Campo inválido (`@Valid`), JSON malformado, parâmetro com tipo errado, `?sort=` com campo inexistente | 400 | `VALIDACAO` | Automático |
| Sem token; token inválido, expirado, de outro issuer ou sem `aud` `app-principal`; usuário inativo ou sem registro local | 401 | `NAO_AUTENTICADO` | Automático (filtro de segurança) |
| Autenticado, mas sem o papel exigido | 403 | `ACESSO_NEGADO` | Automático (filtro ou `@PreAuthorize`) |
| Recurso inexistente **ou de outro cliente** | 404 | `RECURSO_NAO_ENCONTRADO` | `throw new RecursoNaoEncontradoException()` |
| Bloqueio otimista: registro alterado por outra operação (RN-117) | 409 | `CONFLITO_VERSAO` | Automático (`ObjectOptimisticLockingFailureException`) |
| Conflito com registro existente, ex.: conta no Keycloak com mesmo username ou e-mail (RN-127) | 409 | ver catálogo de conflitos | `throw new ConflitoException(CodigoErro.X, "mensagem")` (ex.: `UsuarioJaExisteException`) |
| Arquivo enviado acima de 10 MB (RN-75) | 413 | `ARQUIVO_MUITO_GRANDE` | Automático (`MaxUploadSizeExceededException`); o serviço lança a mesma exceção como defesa |
| Evidência expurgada: o arquivo não existe mais (RN-81, RN-104) | 410 | `EVIDENCIA_EXPURGADA` | `throw new ResponseStatusException(HttpStatus.GONE, "mensagem")`; o handler dá o código pelo status |
| Regra de negócio violada | 422 | ver catálogo | `throw new RegraNegocioException(CodigoErro.X, "mensagem")` |
| Outros erros do Spring MVC (405, 406, 415…) | o do Spring | `REQUISICAO_INVALIDA` | Automático |
| Erro inesperado | 500 | `ERRO_INTERNO` | Automático; a mensagem real só vai para o log |
| Serviço externo fora do ar, lento além do timeout ou com 5xx (RN-137) | 503 | `SERVICO_INDISPONIVEL` | `throw new ServicoIndisponivelException("mensagem", causa)` (ex.: `IdpIndisponivelException`); a mensagem ao cliente é fixa e a causa vai para o log |

**Isolamento (RN-126):** recurso de outro cliente retorna **404, nunca 403**, para não revelar que existe. O serviço busca já filtrando pelo cliente do token e, se não achar, lança `RecursoNaoEncontradoException`, a mesma de quando o id não existe.

## Catálogo de conflitos (409)

| Código | Situação |
| --- | --- |
| `USUARIO_JA_EXISTE` | Já existe conta no Keycloak com o mesmo username ou e-mail (RN-127) |
| `CODIGO_CONTRATO_JA_EXISTE` | Já existe contrato com o mesmo código (cadastro-02) |
| `NOME_POLITICA_JA_EXISTE` | Já existe política de SLA com o mesmo nome (cadastro-03) |

## Arquivo grande demais (413)

| Código | Quando |
| --- | --- |
| `ARQUIVO_MUITO_GRANDE` | Arquivo enviado acima de 10 MB (RN-75): `MaxUploadSizeExceededException`, do limite de multipart ou da conferência do serviço (evidencia-02). Nada é gravado. |

## Evidência expurgada (410)

| Código | Quando |
| --- | --- |
| `EVIDENCIA_EXPURGADA` | `GET /api/evidencias/{id}/conteudo` de evidência `EXPURGADA` (RN-81, evidencia-04). A listagem do chamado continua mostrando os metadados. |

## Catálogo de regras de negócio (422)

Fica em `shared.api.CodigoErro`. Novo código entra lá, com a RN em comentário, e nesta tabela.

| Código | Regra |
| --- | --- |
| `TRANSICAO_INVALIDA` | Transição fora da máquina de estados ou a partir de estado final (RN-11, RN-13) |
| `POSICIONAMENTO_JA_REGISTRADO` | O primeiro posicionamento do chamado já foi registrado (RN-23) |
| `CONTRATO_FORA_VIGENCIA` | Chamado aberto sob contrato sem vigência ativa (RN-01) |
| `CONTRATO_SEM_POLITICA` | Contrato sem política de SLA vinculada (RN-03); lançado por `cadastro.api.ContratoConsulta.versaoVigenteDaPolitica` |
| `ORDEM_PRAZOS_INVALIDA` | Política com prazos fora da ordem resposta < campo < resolução (RN-30, RN-32) |
| `EVIDENCIA_OBRIGATORIA` | Conclusão de atendimento sem evidência (RN-72) |
| `DESCRICAO_TECNICA_OBRIGATORIA` | Conclusão de atendimento sem a descrição técnica (RN-72, chamado-11) |
| `CANCELAMENTO_NAO_PERMITIDO` | Cancelamento a partir de `RESOLVIDO` ou depois (RN-19) |
| `REATRIBUICAO_NAO_PERMITIDA` | Reatribuição fora de `ATRIBUIDO`/`EM_ATENDIMENTO` (RN-20) |
| `REABERTURA_NAO_PERMITIDA` | Reabertura fora de `RESOLVIDO` (RN-93, RN-99) |
| `POLITICA_INEXISTENTE` | Política de SLA informada no vínculo com o contrato não existe (cadastro-04) |
| `CLIENTE_OBRIGATORIO` | Usuário de perfil CLIENTE sem a empresa cliente (RF-09) |
| `CLIENTE_INEXISTENTE` | Empresa cliente informada não existe (RF-09, RF-06) |
| `CLIENTE_INATIVO` | Empresa cliente inativa não aceita novo usuário nem novo contrato (cadastro-01, cadastro-02) |
| `CLIENTE_NAO_PERMITIDO` | Técnico ou gestor informado com empresa cliente (RF-09) |
| `SUBSTITUTO_OBRIGATORIO` | Técnico com chamados não finalizados inativado sem substituto (RN-133) |
| `SUBSTITUTO_INVALIDO` | Substituto inexistente, inativo, não técnico ou o próprio inativado (RN-133) |
| `AUTOINATIVACAO_NAO_PERMITIDA` | Gestor inativando a si mesmo (autenticação-06) |
| `ULTIMO_GESTOR_ATIVO` | Inativação do último gestor ativo (autenticação-06) |
| `FORMATO_NAO_SUPORTADO` | Os bytes do arquivo enviado não são JPEG, PNG nem WebP; extensão e Content-Type não contam (RN-75, evidencia-02) |
| `IMAGEM_MUITO_GRANDE` | O cabeçalho da imagem declara mais megapixels que `app.evidencia.teto-megapixels` (RN-75, evidencia-02) |
| `IMAGEM_INVALIDA` | Assinatura de imagem válida, mas a imagem está corrompida ou não decodifica (RN-75, evidencia-02) |
| `REPROCESSAMENTO_NAO_PERMITIDO` | Reprocessamento de evidência que não está em `FALHA` (RN-121, evidencia-05) |
