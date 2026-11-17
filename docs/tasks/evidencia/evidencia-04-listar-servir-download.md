# [evidência - 04] Listar evidências e servir o download

> Requisitos: RF-26, RF-28, RN-79, RN-80, RN-81 · Depende de: [evidência - 02], [evidência - 03], [chamado - 04]

## Contexto

Com as evidências gravadas e processadas, os três perfis precisam vê-las: a listagem por atendimento e o download da imagem (visualização, miniatura ou original). O acesso segue o do chamado (RN-126) e o arquivo sai em streaming, sem carregar até 10 MB na memória. Não há exclusão: o ciclo de vida é a retenção (RN-79, RN-108).

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `GET /api/chamados/{id}/evidencias` lista por atendimento, com estado, tamanho, data e hash | `EvidenciaController.listar` agrupa (por atendimento, na ordem de `enviadaEm`) as linhas de `ConsultaEvidenciaService.listar` em `{atendimentoId, evidencias: [{id, nome, tipoConteudo, tamanho, hashSha256, estado, enviadaEm, expurgadaEm}]}`. A `EXPURGADA` fica na lista (metadados, RN-104). |
| `GET /api/evidencias/{id}/conteudo` serve visualização por padrão, miniatura ou original quando pedido, e o original se ainda não houver derivados | `?variante=visualizacao\|miniatura\|original` (padrão `visualizacao`). `ConsultaEvidenciaService.localizar` escolhe a chave; derivado nulo (RECEBIDA, PROCESSANDO, FALHA) cai no original (RN-81). |
| Cliente de outra empresa recebe 404 | `AcessoChamado.podeAcessar(usuario, evidencia.chamadoId)`; sem acesso, evidência inexistente e chamado inexistente lançam `RecursoNaoEncontradoException`. Vale também para o técnico não atribuído. |
| Evidência EXPURGADA retorna 410 | `ResponseStatusException(GONE)` com o código novo `EVIDENCIA_EXPURGADA` (o `GlobalExceptionHandler` mapeia o 410). |
| Download de 10 MB não carrega o arquivo inteiro em memória | `StreamingResponseBody` com `transferTo` do `InputStream` do MinIO, fechado em `try-with-resources`. Prova no teste `shouldStreamTenMegabytesWithoutReadingTheWholeObjectBeforeResponding`. |
| Não existe endpoint de exclusão | Nenhum `@DeleteMapping` com "evidencia" no caminho; teste percorre o `RequestMappingHandlerMapping` e confere que um `DELETE` responde 404/405. |
| Testes de integração criados e passando | `ConsultaEvidenciaTest` (12 testes). |

## Passos técnicos realizados

- [x] Listagem com `chamado.podeAcessar` (`AcessoChamado`), em `ConsultaEvidenciaService`; `EvidenciaRepository.findByChamadoIdOrderByEnviadaEmAscIdAsc`.
- [x] Endpoint de conteúdo com `StreamingResponseBody`; `Content-Type`, `Content-Length`, `ETag` (hash) e `Cache-Control: private, no-cache`.
- [x] `EvidenciaRegrasAcesso`: `GET /api/evidencias/**` para os três papéis, mais `@PreAuthorize`. A listagem já cai na regra `GET /api/chamados/**` do chamado.
- [x] Código `EVIDENCIA_EXPURGADA` em `CodigoErro`, `GlobalExceptionHandler`, `docs/erros-api.md` e na OpenAPI (`RespostasErro.EVIDENCIA_EXPURGADA`).
- [x] `EvidenciaController` passou a ter `@RequestMapping("/api")` (os caminhos de `/chamados/...` e `/evidencias/...` são completos nos métodos).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Quem lista | Os três papéis, só pelo isolamento do chamado | A regra de rota do chamado já cobre `GET /api/chamados/**`; o `@PreAuthorize` repete os papéis. |
| Formato da listagem | Lista de `{atendimentoId, evidencias[]}`, sem paginação | Poucas evidências por chamado. Atendimentos na ordem da primeira evidência. |
| Variante | Parâmetro `variante`, constantes do enum em minúsculas (`visualizacao`, `miniatura`, `original`) | O nome da constante é o valor da query (a conversão do Spring é por nome exato) e entra no ETag. Valor inválido: 400 `VALIDACAO` pelo handler. |
| Ordem das conferências | Evidência/acesso (404) → EXPURGADA (410) → `If-None-Match` (304) → abre o objeto | Quem não vê o chamado nunca descobre nada pelo ETag; o 304 não toca no storage. |
| 410 | `ResponseStatusException(HttpStatus.GONE)` e `case 410` no `codigoPara` do handler | Menor mudança: o `ResponseEntityExceptionHandler` já trata a exceção; só faltava o código, no mesmo padrão do 413. |
| Cabeçalhos | `Content-Type` `image/jpeg` (derivado) ou o tipo gravado (original); `Content-Length` do tamanho que o storage devolve; `ETag` forte `"{hash}-{variante servida}"`; `Cache-Control: private, no-cache`; `Content-Disposition: inline` (sem `filename`: o nome pode ter dado pessoal) | O hash é do original, então a variante distingue os bytes; se caiu no original por falta de derivado, a variante é `original` (e o ETag muda quando o derivado nasce). O conteúdo de uma chave nunca muda (RN-77), mas o acesso muda: por isso sem `max-age` (ver "Ajustes da revisão"). `nosniff` já vem do Spring Security. |
| 304 | `WebRequest.checkNotModified(etag)` antes de abrir o objeto | Barato: nada de MinIO. |
| Streaming | O objeto é aberto **antes** de retornar o `ResponseEntity`; a lambda só copia e fecha | 404/503 do storage saem como erro normal e há `Content-Length`. Nada de acesso lazy do JPA dentro da lambda (`open-in-view=false`). A segurança não bloqueia o dispatch assíncrono (verificado no teste com servidor real). |
| Prova da memória | Teste com servidor real e o `InputStream` do MinIO embrulhado num stream que trava depois de ~1 MB lido (latch); o cliente tem de receber 512 KB com o servidor travado | Observável e determinístico, sem medir heap. |
| Exclusão | Não existe; teste percorre o mapeamento do Spring | RN-79, RN-108. |
| Cabeçalhos de segurança | `HeaderWriterFilter` com `shouldWriteHeadersEagerly=true` na `SegurancaConfig` (decisão do usuário) | O corpo do `StreamingResponseBody` é escrito por outra thread enquanto a da requisição volta pelos filtros; gravados depois, os cabeçalhos de segurança disputavam a mesma resposta (`ConcurrentModificationException` na suíte completa) e podiam se perder. Antecipados, vão antes do controller. Vale para toda a API; efeito colateral: o download leva também `Pragma: no-cache` e `Expires: 0`, e o `Cache-Control: private, no-cache` do controller prevalece. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável de ambiente nova.
- O `Cache-Control` é `private, no-cache` (sem `max-age`): o navegador revalida a cada uso, então revogar o acesso (reatribuição, por exemplo) vale na hora. Cópias guardadas antes desta correção, com `max-age=86400`, ainda valem por até 1 dia no navegador de quem já baixou.

## Como verificar

Ver `docs/testes/evidencia/evidencia-04-listar-servir-download.md` e a seção 7I do `docs/plano-testes-manuais.md`.

## Ajustes da revisão (2026-11-16)

**A. `Cache-Control` do download.** Problema: `max-age=86400, private` deixava o técnico antigo, depois da reatribuição do chamado, continuar vendo a foto pelo cache do navegador por até 1 dia. Correção: `Cache-Control: private, no-cache` na resposta 200 e na 304 (`CacheControl.noCache().cachePrivate()` no `EvidenciaController`; descrição do endpoint e `docs/openapi.json` ajustados). Decisão (do usuário): o navegador guarda, mas revalida sempre com `If-None-Match`; o caminho do 304 já confere o acesso (404) e o `EXPURGADA` (410) antes de `checkNotModified`, então quem perdeu o acesso recebe 404 e a economia de banda continua para quem tem acesso.

**B. `HEAD` no download.** Suspeita: o objeto é aberto no MinIO antes de o `ResponseEntity` ser devolvido e o stream só é fechado na lambda do corpo; num `HEAD` (atendido pelo `@GetMapping`) o corpo poderia nunca ser escrito e o stream vazar. Observado com servidor real (spy no `S3Client` contando `getObject` e `close()`): **não vaza**. O Spring executa a lambda também no `HEAD` (o Tomcat descarta os bytes), e `abertos=1 fechados=1`; os cabeçalhos do `HEAD` são iguais aos do `GET`. Decisão: código inalterado; teste mantido como regressão. Achado a registrar: como a lambda roda, um `HEAD` ainda lê o objeto inteiro do MinIO e o descarta (desperdício de banda interna, sem vazamento). Só vale tratar (`headObject` para o tamanho, sem abrir o objeto) se `HEAD` passar a ser usado de verdade; ninguém decidiu isso.
