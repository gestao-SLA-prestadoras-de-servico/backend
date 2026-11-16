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
| `shouldSendContentTypeLengthEtagAndPrivateCacheControl` | `Content-Type` (`image/jpeg` no derivado, `image/png` no original), `Content-Length` igual ao corpo, um único `ETag` `"{hash}-{variante}"`, `Cache-Control` com `private` e sem `public`, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`. |
| `shouldAnswer304WithoutReadingTheStorageWhenIfNoneMatchMatches` | `If-None-Match` igual: 304 sem corpo, com `Cache-Control` e um só `ETag`, **zero `getObject` no `S3Client`** (spy); ETag de outra variante não vale (200); quem não vê o chamado recebe 404, não 304. |
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
| H | `Cache-Control` público | `shouldSendContentTypeLengthEtag...`, `shouldAnswer304...` | o cabeçalho não contém `private` |
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
