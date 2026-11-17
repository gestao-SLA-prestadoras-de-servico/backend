# Testes — [evidência - 04] Listar evidências e servir o download

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`, MinIO, e RabbitMQ no `ProcessadorEvidenciasTest`). As quebras propositais foram feitas no próprio repositório, uma de cada vez, e desfeitas em seguida (conferido com `diff -r` contra uma cópia de `src/main` tirada antes: "diff limpo").

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Unitários | `make unit-test` | tudo passa | 165 testes, 0 falhas | ✅ |
| 2 | Suítes afetadas, JVM em outro fuso | `TZ=UTC ./mvnw clean test -Dtest='ConsultaEvidenciaTest,ReprocessamentoEvidenciaTest,RecepcaoEvidencia*Test,ProcessadorEvidenciasTest,ArmazenamentoEvidenciasTest,AcessoPorRotaTest,ConsultaChamadoTest,OpenApiContratoTest,AuditoriaTest,BackendApplicationTests'` | tudo passa | 86 testes, 0 falhas, 0 erros (12 + 6 + 16 + 1 + 11 + 5 + 9 + 9 + 3 + 8 + 6, nesta ordem: consulta, reprocessamento, recepção, corrida, processador, storage, rotas, chamado, OpenAPI, auditoria, contexto) | ✅ |
| 3 | `ConsultaEvidenciaTest` isolado, 5 vezes | `TZ=UTC ./mvnw -Dtest=ConsultaEvidenciaTest test` | estável | 12 testes, 0 falhas em todas as 5 execuções | ✅ |
| 4 | `docs/openapi.json` | `OpenApiContratoTest` (reescreve e falha na primeira vez; passou na segunda) | contrato em dia | 3 testes, 0 falhas; arquivo igual depois da última execução | ✅ |

### Os testes do `ConsultaEvidenciaTest` (12)

| Teste | O que prova |
| --- | --- |
| `shouldListEvidenceGroupedByAttendanceOrderedBySentDateForAllRoles` | Listagem agrupada por atendimento, ordenada por `enviadaEm`, com id, nome, tipo, tamanho, hash, estado, `enviadaEm` e `expurgadaEm`; igual para gestor, cliente da empresa e técnico atribuído; a `EXPURGADA` aparece. |
| `shouldListEmptyWhenTheChamadoHasNoEvidence` | Chamado sem evidência: 200 e lista vazia. |
| `shouldAnswer404ForOtherCompanyUnassignedTechnicianAndUnknownIds` | Cliente de outra empresa e técnico não atribuído: 404 na listagem e no conteúdo (padrão e `original`); chamado e evidência inexistentes: 404. |
| `shouldAnswer401WithoutToken` | Sem token: 401 nas duas rotas. |
| `shouldServeVisualizationByDefaultThumbnailAndOriginalOnRequest` | Padrão = visualização (1280 px, igual a `?variante=visualizacao`), miniatura 320 px, original com os bytes e o SHA-256 gravados; para os três papéis. |
| `shouldFallBackToOriginalWhenTheDerivativeDoesNotExistYet` | `RECEBIDA`, `PROCESSANDO` e `FALHA`: qualquer variante serve o original, com `Content-Type` do original e ETag `...-original`. |
| `shouldSendContentTypeLengthEtagAndPrivateCacheControl` | `Content-Type` (`image/jpeg` no derivado, `image/png` no original), `Content-Length` igual ao corpo, um único `ETag` `"{hash}-{variante}"`, `Cache-Control` com `private` e `no-cache`, sem `public` nem `max-age`, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`. |
| `shouldAnswer304WithoutReadingTheStorageWhenIfNoneMatchMatches` | `If-None-Match` igual: 304 sem corpo, com `Cache-Control: private, no-cache` e um só `ETag`, **zero `getObject` no `S3Client`** (spy); ETag de outra variante não vale (200); quem não vê o chamado recebe 404, não 304. |
| `shouldAnswer410ForExpungedEvidence` | EXPURGADA: 410 `EVIDENCIA_EXPURGADA` em qualquer variante. |
| `shouldAnswer400ForUnknownVariant` | `?variante=gigante`: 400 `VALIDACAO`. |
| `shouldStreamTenMegabytesWithoutReadingTheWholeObjectBeforeResponding` | Servidor real (RANDOM_PORT), objeto de 10 MB. O `InputStream` do storage é embrulhado (spy no `S3Client`) num stream que **trava depois de 1 MB lido** até um latch ser solto. O cliente (`HttpClient`, `ofInputStream`) recebe 200, `Content-Length` de 10 MB e os primeiros 512 KB (iguais aos do original) em até 15 s, com o servidor ainda travado e menos de 2 MB lidos do storage. Solto o latch, o corpo completo tem 10 MB e o SHA-256 igual. Se o servidor lesse tudo antes de responder, o cliente não receberia nada. |
| `shouldNotExposeAnyEvidenceDeletionEndpoint` | Nenhum mapeamento do Spring com "evidencia" no caminho aceita `DELETE`; `DELETE /api/evidencias/{id}` e `DELETE /api/chamados/{c}/evidencias/{e}` respondem 404, `DELETE /api/chamados/{id}/evidencias` e `DELETE /api/evidencias/{id}/conteudo`, 405; a linha continua no banco. |

## Quebras propositais (cada teste pega o erro)

| # | Quebra no código de produção | Testes que falharam | Falha observada |
| --- | --- | --- | --- |
| A | `listar` sem a conferência `podeAcessar` | `shouldAnswer404ForOtherCompanyUnassignedTechnicianAndUnknownIds` | esperado 404, veio 200 |
| B | `localizar` sem o filtro `podeAcessar` | `...404ForOtherCompany...`, `shouldAnswer304WithoutReadingTheStorage...` | outro cliente recebe 200 em vez de 404 (e o 304 deixa de ser 404) |
| C | `EXPURGADA` deixa de dar 410 | `shouldAnswer410ForExpungedEvidence`, `shouldServeVisualizationByDefault...` | status diferente de 410 (cai na leitura de um objeto que não existe) |
| D | Variante padrão `miniatura` | `shouldServeVisualizationByDefault...`, `shouldSendContentTypeLengthEtag...`, `shouldAnswer304...` | largura 320 em vez de 1280; ETag `-miniatura` |
| E | Derivado nulo dá 404 em vez de cair no original | `shouldFallBackToOriginalWhenTheDerivativeDoesNotExistYet` (e os que dependem da visualização) | esperado 200, veio 404 |
| F | ETag sem a variante (só o hash) | `shouldFallBackToOriginal...`, `shouldSendContentTypeLengthEtag...`, `shouldAnswer304...` | ETag `"{hash}"` em vez de `"{hash}-original"` |
| G | Sem `checkNotModified` | `shouldAnswer304WithoutReadingTheStorage...` | esperado 304, veio 200 |
| H | `Cache-Control` público | `shouldSendContentTypeLengthEtag...`, `shouldAnswer304...` | o cabeçalho não contém `private`/`no-cache` (ou passa a ter `max-age`) |
| I | Corpo lido inteiro (`readAllBytes`) antes de escrever | `shouldStreamTenMegabytesWithoutReadingTheWholeObjectBeforeResponding` | o cliente não recebe os 512 KB (timeout) com o storage travado em 1 MB |
| J | Sem `Content-Length` | `shouldSendContentTypeLengthEtag...`, `shouldStreamTenMegabytes...` | cabeçalho ausente |
| K | Listagem em ordem decrescente (método do repositório e chamada renomeados juntos) | `shouldListEvidenceGroupedByAttendanceOrderedBySentDateForAllRoles` | `atendimentoId` do primeiro grupo é o outro atendimento |
| L | Sem o `case 410` no handler | `shouldAnswer410ForExpungedEvidence` | `codigo` `REQUISICAO_INVALIDA` em vez de `EVIDENCIA_EXPURGADA` |
| M | `@PreAuthorize` do conteúdo só GESTOR | `...404ForOtherCompany...`, `...304...`, `shouldServeVisualizationByDefault...` | técnico e cliente recebem 403 |
| N | Um `@DeleteMapping("/evidencias/{id}")` no controller | `shouldNotExposeAnyEvidenceDeletionEndpoint` | o mapeamento aparece com `DELETE` |
| O | A variante pedida é ignorada (sempre visualização) | `shouldAnswer304...`, `shouldServeVisualizationByDefault...`, `shouldSendContentTypeLengthEtag...` | miniatura e original saem como visualização |
| P | `hashSha256` nulo na listagem | `shouldListEvidenceGroupedBy...` | `$[1].evidencias[0].hashSha256` diferente do hash |
| Q | Lista vazia vira 404 | `shouldListEmptyWhenTheChamadoHasNoEvidence` | esperado 200, veio 404 |
| R | Regra de rota `GET /api/evidencias/**` com `permitAll` | `shouldAnswer401WithoutToken` | esperado 401, veio 403 |
| V | `variante` lida como texto, sempre visualização (o parâmetro deixa de ser `Variante`) | `shouldAnswer400ForUnknownVariant` (e 3 que dependem da variante) | esperado 400, veio 200 |

## Observações

1. **StreamingResponseBody e a segurança:** o teste com servidor real passou e o log não tem erro de autorização no dispatch assíncrono. Com o `MockMvc` o corpo só existe depois do `asyncDispatch` (o helper `baixar` faz isso).
2. **A prova dos 10 MB não mede heap:** ela mostra que os bytes chegam ao cliente enquanto o servidor só leu ~1 MB do storage. Não prova que o buffer interno do servidor (Tomcat) seja pequeno, só que o arquivo não é lido inteiro antes de responder.
3. **Papel por rota e por método (R, M):** quebrar só a regra de rota ou só o `@PreAuthorize` de um endpoint não derruba os testes de papel (uma camada cobre a outra, de propósito); a quebra M troca o `@PreAuthorize` para um papel mais restrito e a R afrouxa a rota.
4. **`ConcurrentModificationException` (corrigida na revisão):** apareceu na quebra B e, depois, na suíte completa com `TZ=UTC` (482 testes, 1 erro em `shouldFallBackToOriginalWhenTheDerivativeDoesNotExistYet`). Causa: o `HeaderWriterFilter` do Spring Security gravava os cabeçalhos de segurança na volta da cadeia de filtros, ao mesmo tempo que a thread do `StreamingResponseBody` escrevia a mesma resposta. Correção: `setShouldWriteHeadersEagerly(true)` na `SegurancaConfig`. Teste novo `shouldWriteSecurityHeadersEagerlySoTheStreamingThreadDoesNotRace` confere a configuração, porque a corrida não se reproduz de forma determinística; com `false` ele falha (`expected: true`), com `true` passa. Depois da correção, `ConsultaEvidenciaTest`, `AutorizacaoTest`, `AcessoPorRotaTest` e `UsuarioMeTest`: 35 testes, 0 falhas.
5. **Não verificado:**
   - o fluxo no ambiente dev (`make run-dev`, seção 7I do plano de testes manuais), incluindo `curl` com os três tipos de variante e o comportamento dos navegadores com `Cache-Control`;
   - a suíte completa com `TZ=UTC ./mvnw clean test` (rodei só as suítes afetadas listadas no item 2);
   - download concorrente de muitos clientes e cliente que aborta no meio (o `try-with-resources` fecha o stream, mas não há teste do aborto);
   - 304 com `If-None-Match` de lista de ETags ou `*` (delegado ao `checkNotModified` do Spring).

## Ajustes da revisão (2026-11-16)

Execução com `TZ=UTC`, branch `feat/evidencia`.

| # | O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | `Cache-Control: private, no-cache`, sem `max-age`, no 200 e no 304 | `shouldSendContentTypeLengthEtagAndPrivateCacheControl`, `shouldAnswer304WithoutReadingTheStorageWhenIfNoneMatchMatches` (ajustados) | passam | passaram | ✅ |
| 2 | Revogação vale na revalidação | `shouldAnswer404NotModifiedToATechnicianWhoLostTheChamadoOnRevalidation` (novo): técnico baixa e pega o ETag, recebe 304; o chamado é reatribuído por SQL; com o mesmo `If-None-Match` o antigo recebe 404 e o novo, 304 | 404 (não 304) para o antigo | passou | ✅ |
| 3 | Quebra: voltar `maxAge(1 dia)` | `-Dtest=ConsultaEvidenciaTest` | testes de cabeçalho falham | 2 falhas (`shouldSendContentTypeLength...:228`, `shouldAnswer304...:253`); o teste 2 continua passando, pois a revogação é do lado do servidor | ✅ |
| 4 | Quebra: `checkNotModified` antes da conferência de acesso | idem | testes de revogação falham | 2 falhas (`shouldAnswer304...:258` e `shouldAnswer404NotModified...:279`: 304 em vez de 404) | ✅ |
| 5 | `HEAD` com servidor real (`HttpClient`, método `HEAD`), spy no `S3Client` embrulhando o `InputStream` | `shouldAnswerHeadWithTheSameHeadersAsGetAndLeaveNoStorageStreamOpen` (novo): cabeçalhos iguais aos do GET, corpo vazio, streams abertos = fechados | sem vazamento, se a suspeita for falsa | `abertos=1 fechados=1` (leitura temporária do contador), passou | ✅ |
| 6 | Quebra do teste 5 | não feita: como não há vazamento, não há correção a desfazer; a asserção só falharia se o stream deixasse de ser fechado | n/a | **não verificado** que o teste falha com um vazamento real | n/a |
| 7 | Regressão | `ConsultaEvidenciaTest` 15, `OpenApiContratoTest` 3 (a 1ª execução reescreveu `docs/openapi.json` e falhou, como esperado; a 2ª passou) | 0 falhas | 0 falhas | ✅ |

Observações: o `HEAD` lê o objeto inteiro do MinIO e o descarta (ver a task). Não verificado: comportamento dos navegadores com `no-cache`. O fluxo no ambiente dev foi verificado (seção abaixo).

## Verificação no ambiente dev (2026-11-16)

Branch `feat/evidencia`, `make ci-run-dev` com os volumes de dev existentes (o Flyway aplicou até a V15 e o log mostrou "Bucket evidencias criado"). Massa criada pela própria API (dois clientes com contrato vigente, técnicos e clientes novos, senha definida pela Admin API do Keycloak) e chamado levado a `EM_ATENDIMENTO`. Os tokens vieram do client `flowsla-swagger` por Authorization Code + PKCE, sem navegador. O roteiro das seções 7G a 7J do `docs/plano-testes-manuais.md` foi automatizado em script, fora do repositório.

| Seção | Resultado |
| --- | --- |
| 7G (envio) | 202 `RECEBIDA`; objeto em pé (2000x4000 a partir de 4000x2000 com orientação 6), EXIF vazio, cores na posição certa e `sha256sum` igual ao `hash_sha256`; auditoria sem o nome do arquivo; evento só com referências; PDF disfarçado 422 `FORMATO_NAO_SUPORTADO`; 11 MB 413 `ARQUIVO_MUITO_GRANDE`; técnico não atribuído 404; gestor 403; contagem no detalhe igual às linhas do banco. |
| 7H (processamento) | `DISPONIVEL` em até 5 s; derivados 160x320 e 640x1280; original intacto; fila principal vazia; métrica `evidencias{estado="DISPONIVEL"}` exposta; MinIO parado depois do envio: `FALHA` em cerca de 20 s e uma mensagem nova na DLQ; envio com o MinIO parado: 503 `SERVICO_INDISPONIVEL`; chamado e atendimento inalterados. |
| 7I (listar e baixar) | Listagem 200 para gestor, técnico e cliente; 404 para cliente de outra empresa e técnico não atribuído; `ETag`, `Cache-Control: no-cache, private`, `Content-Type`, `Content-Length`, `Content-Disposition` e `nosniff`; visualização 1280 por padrão, miniatura 320, original com o hash; 304 com `If-None-Match`; `HEAD` 200 sem corpo; variante inválida 400; `FALHA` serve o original; expurgada 410 e ainda listada; `DELETE` 404. |
| 7J (reprocessar) | Técnico 403; gestor 202 `RECEBIDA`; auditoria `{"de": "FALHA", "para": "RECEBIDA"}` e dois eventos distintos; `DISPONIVEL` em até 3 s; repetido 422 `REPROCESSAMENTO_NAO_PERMITIDO`; inexistente 404. |

Achados:

1. **Plano manual 7H.5 corrigido:** ele mandava parar o MinIO e então enviar a foto, mas aí o envio recebe 503 (correto) e nada chega ao processamento. A falha do processamento exige parar o MinIO **depois** do 202 e antes do publicador rodar.
2. **Cache negativo de DNS da JVM:** logo depois de religar o MinIO, por cerca de 10 s a aplicação ainda responde 503 (`UnknownHostException: minio`, guardada pela JVM enquanto o container estava parado). Volta sozinha; não foi alterado.
3. **Contagem da DLQ no console:** as estatísticas do RabbitMQ atualizam com alguns segundos de atraso; logo após a `FALHA` a DLQ pode aparecer vazia.

Fora do roteiro executado: 7G.10 (chamado ATRIBUIDO), 7J.7 (mover a mensagem da DLQ pelo console) e a inspeção dos objetos pelo console do MinIO (a imagem `minio/mc` não existe mais no Docker Hub; os objetos foram baixados pela API e conferidos pelo hash).
