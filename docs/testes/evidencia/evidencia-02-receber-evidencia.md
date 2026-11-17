# Testes — [evidência - 02] Receber evidência: validar, sanitizar e gravar

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17` e a imagem do MinIO do compose). As quebras propositais foram feitas no próprio repositório, uma de cada vez, e desfeitas em seguida (conferido com `diff -r` contra uma cópia de `src/` tirada antes).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Testes novos | `./mvnw -Dtest='SanitizadorImagemTest,RecepcaoEvidencia*Test' test` | tudo passa | 15 + 16 + 1 testes, 0 falhas | ✅ |
| 2 | Unitários | `make unit-test` (inclui `ModularidadeTest`, `SegurancaArquiteturaTest`, `RelogioArquiteturaTest`) | tudo passa | 160 testes, 0 falhas (145 anteriores + 15 novos) | ✅ |
| 3 | Integração afetada, com a JVM em outro fuso | `TZ=UTC ./mvnw -Dtest='RecepcaoEvidencia*Test,AcessoPorRotaTest,ConsultaChamadoTest,BackendApplicationTests,OpenApiContratoTest,ArmazenamentoEvidenciasTest,InicioAtendimentoTest' test` | tudo passa | 60 testes, 0 falhas | ✅ |
| 4 | Outras suítes que tocam auditoria, outbox e contexto | `AuditoriaTest,OutboxTest,PublicadorOutboxTest` junto dos anteriores (sem `TZ`) | tudo passa | 77 testes no total, 0 falhas | ✅ |
| 5 | Contrato OpenAPI | `OpenApiContratoTest` duas vezes: a primeira reescreve `docs/openapi.json` (falha por endpoint novo), a segunda confere | passa na segunda | 1ª: 1 falha (esperada); 2ª: 3 testes, 0 falhas. Diff: endpoint `POST /api/chamados/{id}/evidencias`, schema `EvidenciaRecebida` e resposta `ArquivoMuitoGrande` | ✅ |
| 6 | 202 com linha, hash, auditoria e evento | `RecepcaoEvidenciaTest.shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | 202 `RECEBIDA`; linha `RECEBIDA` com a chave `evidencias/{chamado}/{id}/original.jpg`, `enviada_em` do Clock e hash = SHA-256 do objeto lido do MinIO; auditoria `ENVIO_EVIDENCIA` sem o nome do arquivo; evento `PROCESSAR_EVIDENCIA` (exchange `evidencias`, routing key `processamento`) com só `evidenciaId`, `chamadoId`, `chaveOriginal` e `eventoId` | passou | ✅ |
| 7 | Foto de celular (GPS + orientação 6) | `shouldStorePhoneUprightWithoutAnyExif` (JPEG com APP1 Exif montado à mão por `FotoTeste`, lido de volta do MinIO) | largura e altura trocadas, azul no topo-esquerda e vermelho no topo-direita (90° horário), sem diretório Exif nem GPS no metadata-extractor e sem a sequência `Exif` nos bytes | passou | ✅ |
| 8 | As oito orientações | `SanitizadorImagemTest.shouldApplyExifOrientationToPixels` (1 a 8; cores dos quatro cantos e dimensões, de uma tabela escrita à mão a partir da especificação EXIF) | os oito casos passam | passou | ✅ |
| 9 | EXIF e GPS removidos | `SanitizadorImagemTest.shouldRemoveExifAndGpsFromJpeg` (confere antes que o original tem GPS e orientação 6) | nada de Exif/GPS na saída | passou | ✅ |
| 10 | PDF renomeado para `.jpg` | `shouldRejectPdfRenamedToJpgAndStoreNothing` (`foto.jpg`, `image/jpeg`) e `SanitizadorImagemTest.shouldDecideFormatBySignatureAndRejectTheRest` (PDF, texto, vazio, JPEG truncado na assinatura) | 422 `FORMATO_NAO_SUPORTADO`; nada no storage, no banco nem na auditoria | passou | ✅ |
| 11 | Imagem corrompida | `shouldRejectCorruptedImage`, `SanitizadorImagemTest.shouldRejectCorruptedImageWithValidSignature` (JPEG truncado, PNG só com assinatura, RIFF sem WebP) | 422 `IMAGEM_INVALIDA` | passou | ✅ |
| 12 | Teto de megapixels antes de decodificar | `shouldRejectHugeDimensionsBeforeDecoding` (PNG de 33 bytes com IHDR 100000x100000) e `SanitizadorImagemTest.shouldRejectHugeDimensionsFromHeaderWithoutDecoding` (esse PNG e um JPEG que declara 65535x65535, com limite de 2 s) | 422 `IMAGEM_MUITO_GRANDE` rápido, sem `OutOfMemoryError`; nada gravado | passou | ✅ |
| 13 | Teto exato | `SanitizadorImagemTest.shouldApplyTheMegapixelCeilingExactly` (teto de 1 MP: 1000x1000 passa, 1001x1000 não) | limite inclusivo | passou | ✅ |
| 14 | Acima de 10 MB pela configuração real | `shouldRejectFileOverTenMegabytesWith413AndStoreNothing` (`RANDOM_PORT`, `HttpClient`, 10 MB + 100 KB) | 413 `ARQUIVO_MUITO_GRANDE`; `listar("evidencias/{chamado}/")` vazio | passou | ✅ |
| 15 | Defesa do serviço contra o mesmo caso | `shouldRejectOversizedFileInTheServiceToo` (MockMvc não aplica o limite do servidor; 10 MB + 1 byte) | 413 `ARQUIVO_MUITO_GRANDE`; nada gravado | passou | ✅ |
| 16 | WebP aceito e gravado como JPEG | `shouldAcceptWebpAndStoreItAsJpeg` e `SanitizadorImagemTest.shouldConvertWebpToJpeg` com `src/test/resources/evidencia/vermelho-azul.webp` | chave `original.jpg`, `image/jpeg`, cores preservadas | passou | ✅ |
| 17 | PNG continua PNG, com alfa | `shouldKeepPngAsPng`, `SanitizadorImagemTest.shouldKeepPngAsPngWithTransparency` | `original.png`, `image/png`, alfa preservado | passou | ✅ |
| 18 | Nome do arquivo | `shouldKeepOnlyTheBaseNameOfTheUploadedFileName` (`C:\Users\...\aaaa...jpg`) | sem path, 255 caracteres | passou | ✅ |
| 19 | Quem pode enviar | `shouldAnswer404ToTechnicianNotAssignedAnd403ToOtherRoles`, `shouldAnswer404ForUnknownChamado` | outro técnico e chamado inexistente: 404; gestor e cliente: 403; sem token: 401; nada gravado | passou | ✅ |
| 20 | Estado | `shouldRejectWhenThereIsNoAttendanceInProgress` (atendimento PENDENTE, chamado ATRIBUIDO sem atendimento, chamado RESOLVIDO com atendimento CONCLUIDO) | 422 `TRANSICAO_INVALIDA`; nenhuma linha nem evento | passou | ✅ |
| 21 | Objeto órfão | `shouldLeaveOrphanObjectAndNothingInTheDatabaseWhenTransactionFails`: trigger temporária no banco que falha o INSERT do evento (removida no `finally`; só vale para o tipo `PROCESSAR_EVIDENCIA`, sem poluir outros testes nem criar contexto novo) | 500 `ERRO_INTERNO`; 1 objeto em `evidencias/{chamado}/`; nenhuma linha em `evidencia`, nenhuma auditoria `ENVIO_EVIDENCIA`, nenhum evento | passou | ✅ |
| 22 | Conferência refeita na transação | `RecepcaoEvidenciaCorridaTest` (`@MockitoSpyBean` em `AtendimentoConsulta`: a 1ª consulta é real, a 2ª responde vazio, como numa reatribuição entre as duas) | 422 `TRANSICAO_INVALIDA`; objeto órfão no storage; nenhuma linha nem evento | passou | ✅ |
| 23 | Porta do chamado | `shouldShowEvidenceCountPerAttendanceInChamadoDetail` | `GET /api/chamados/{id}` traz `atendimentos[0].evidencias = 2` | passou | ✅ |
| 24 | Imutabilidade (RN-77) | `shouldRejectChangingHashOrOriginalKeyEvenBySql` | `UPDATE` de `hash_sha256` ou `chave_original` falha com a mensagem da trigger; `UPDATE` de `estado` e `chave_miniatura` funciona | passou | ✅ |

## Quebras propositais (cada teste pega o erro)

Cada linha: a alteração feita no código de produção (e desfeita), os testes que passaram a falhar e a falha observada.

| # | Quebra | Testes que falharam | Falha observada |
| --- | --- | --- | --- |
| Q1 | `case 6` da orientação sem transformação | `shouldApplyExifOrientationToPixels[6]` | `...:58` (largura/altura não trocadas) |
| Q2 | `case 7` com a matriz da orientação 6 | `shouldApplyExifOrientationToPixels[7]` | `...:62 [topo-esquerda]` |
| Q3 | JPEG devolvido sem regravar (EXIF mantido) | `shouldRemoveExifAndGpsFromJpeg`, `shouldApplyExifOrientationToPixels[2..8]`, `shouldStorePhoneUprightWithoutAnyExif`, `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | 8 falhas no unitário; no de integração, o objeto igual ao enviado (`...:114`, `...:139`) |
| Q4 | WebP tratado como PNG (`png = !formato.equals("jpeg")`) | `shouldConvertWebpToJpeg`, `shouldAcceptWebpAndStoreItAsJpeg` | `...Test:91` e `...:212` |
| Q5 | Sem alfa na saída do PNG | `shouldKeepPngAsPngWithTransparency` | `...:109` |
| Q6 | Assinatura desconhecida cai em "jpeg" em vez de recusar | `shouldDecideFormatBySignatureAndRejectTheRest`, `shouldRejectPdfRenamedToJpgAndStoreNothing` | código `IMAGEM_INVALIDA` no lugar de `FORMATO_NAO_SUPORTADO` |
| Q7 | Teto de megapixels desligado (`if (false)`) | `shouldRejectHugeDimensionsFromHeaderWithoutDecoding`, `shouldApplyTheMegapixelCeilingExactly`, `shouldRejectHugeDimensionsBeforeDecoding` | `IMAGEM_INVALIDA` (a decodificação falha por falta de dados) no lugar de `IMAGEM_MUITO_GRANDE`; `1 megapixels` aceito |
| Q8 | Decodifica (`read(0)`) antes de conferir o teto | `shouldRejectHugeDimensionsFromHeaderWithoutDecoding` | falha (o cabeçalho sem pixels não decodifica) |
| Q9 | Teto com `>=` | `shouldApplyTheMegapixelCeilingExactly` | `RegraNegocio A imagem tem mais de 1 megapixels.` para 1000x1000 |
| Q10 | Imagem corrompida vira `FORMATO_NAO_SUPORTADO` | `shouldRejectCorruptedImageWithValidSignature`, `shouldRejectCorruptedImage` | código trocado |
| Q11 | Sem a conferência de acesso | `shouldAnswer404ToTechnicianNotAssignedAnd403ToOtherRoles`, `shouldAnswer404ForUnknownChamado` | 404 esperado, veio 202 (outro técnico) e 422 (inexistente) |
| Q12 | Atendimento de qualquer estado aceito | `shouldRejectWhenThereIsNoAttendanceInProgress` | 422 esperado, veio 202 |
| Q13 | Sem a conferência de tamanho no serviço | `shouldRejectOversizedFileInTheServiceToo` | 413 esperado, veio 422 |
| Q14 | `max-file-size=100MB` **sozinho** | nenhum | passa: o `max-request-size=11MB` e a conferência do serviço cobrem o mesmo caso. Com Q14 e Q13 juntos (`max-file-size=100MB` e sem a conferência), `shouldRejectFileOverTenMegabytesWith413AndStoreNothing` e `shouldRejectOversizedFileInTheServiceToo` falham (422). Ou seja, os três guardas se cobrem; o teste de 10 MB real prova que ao menos um segura |
| Q15 | Hash calculado sobre os bytes enviados | `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | `...:112` (hash diferente do objeto gravado) |
| Q16 | Gravação no storage depois da transação | `shouldLeaveOrphanObjectAndNothingInTheDatabaseWhenTransactionFails` | `...:290` (nenhum objeto no storage) |
| Q17 | Nome do arquivo na auditoria | `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | `...:119` |
| Q18 | Binário (base64) no payload do evento | `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | `...:125` (chaves do payload) |
| Q19 | Conferência não refeita na transação | `RecepcaoEvidenciaCorridaTest` | 422 esperado, veio 202 |
| Q20 | Sem o evento na outbox | `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent`, `shouldLeaveOrphanObjectAndNothingInTheDatabaseWhenTransactionFails` | `EmptyResultDataAccess` (nenhum evento); 500 esperado, veio 202 |
| Q21 | `Instant.now()` no lugar do `Clock` | `shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent` | `...:108` (`enviada_em`) |
| Q22 | Trigger de imutabilidade sem efeito (`IF false`) | `shouldRejectChangingHashOrOriginalKeyEvenBySql` | `...:313` |
| Q23 | `ContagemEvidencias` devolvendo mapa vazio | `shouldShowEvidenceCountPerAttendanceInChamadoDetail` | `$.atendimentos[0].evidencias` esperado 2, veio 0 |
| Q24 | 413 sem o código `ARQUIVO_MUITO_GRANDE` no `GlobalExceptionHandler` | `shouldRejectFileOverTenMegabytesWith413AndStoreNothing`, `shouldRejectOversizedFileInTheServiceToo` | `REQUISICAO_INVALIDA` no lugar de `ARQUIVO_MUITO_GRANDE` |
| Q25 | Sem a remoção do path do nome | `shouldKeepOnlyTheBaseNameOfTheUploadedFileName` | `...:230` |
| Q26 | `RegrasAcesso` **e** `@PreAuthorize` abertos a qualquer autenticado | `shouldAnswer404ToTechnicianNotAssignedAnd403ToOtherRoles` | 403 esperado, veio 202 |

## Observações

1. **Duas barreiras de papel:** quebrar só a `RegrasAcesso` ou só o `@PreAuthorize` não muda nenhuma resposta (a outra barreira segura), como já avisa o `SegurancaArquiteturaTest`; por isso Q26 quebra as duas. A existência do `@PreAuthorize` é conferida por aquele teste.
2. **Fixture:** a foto de celular é gerada em memória por `FotoTeste` (JPEG de quatro quadrantes com um segmento APP1 Exif montado à mão: orientação e GPS). Não havia `exiftool`. O WebP (`src/test/resources/evidencia/vermelho-azul.webp`, 48 bytes, 40x20, lado esquerdo vermelho e direito azul, sem perdas) foi gerado com Pillow: `Image.new("RGB",(40,20),(255,0,0))`, azul colado de x=20 a 40, `save(..., lossless=True)`. O TwelveMonkeys só lê WebP, então não há como gerá-lo em Java.
3. **O que Q7 e Q8 provam:** que a conferência do teto existe e vem antes da leitura dos pixels. Elas não medem memória: o teste confia no fato de que decodificar 100000x100000 falharia (erro de dados ou de memória).
4. **O teste do órfão** usa uma trigger criada e removida pelo próprio teste, no mesmo banco dos demais testes da classe (sequenciais); ela só atua em eventos `PROCESSAR_EVIDENCIA`.
5. **Não verificado:**
   - ~~o fluxo no ambiente dev (seção 7G)~~ (verificado no dev em 2026-11-16: ver "Verificação no ambiente dev" na evidencia-04); ficaram fora o 7G.10 (chamado ATRIBUIDO) e o download pelo console do MinIO (o objeto foi baixado pela API, conferido pelo hash);
   - a suíte completa com `TZ=UTC ./mvnw clean test` (rodei apenas as suítes afetadas, citadas acima);
   - upload de arquivos reais de câmera (HEIC não é aceito; JPEG com perfil CMYK, JPEG progressivo e WebP com perfil de cor não foram exercitados);
   - consumo de heap real com uma imagem próxima de 40 MP (o teto é calibração a confirmar com o heap de produção);
   - concorrência (dois envios simultâneos ao mesmo atendimento);
   - o publicador da outbox entregando o `PROCESSAR_EVIDENCIA` à fila (sem consumidor ainda; a topologia existe em `infra/rabbitmq`).
