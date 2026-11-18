# Testes — [evidência - 01] Criar o adaptador do object storage (MinIO)

> Executados em 16/11/2026, na branch `feat/evidencia` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17` e `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, a imagem do compose). As quebras propositais foram feitas no próprio repositório e desfeitas em seguida (código restaurado a partir de cópia).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Testes novos | `./mvnw -Dtest='ArmazenamentoEvidenciasTest,ChaveObjetoTest' test` | tudo passa | 5 + 1 testes, 0 falhas | ✅ |
| 2 | Unitários | `make unit-test` | tudo passa | 145 testes, 0 falhas | ✅ |
| 3 | Contextos existentes com o MinIO no `TestcontainersConfiguration` | `./mvnw -Dtest='ArmazenamentoEvidenciasTest,BackendApplicationTests,OutboxTest' test` | tudo passa | 16 testes, 0 falhas (`BackendApplicationTests` 6, `OutboxTest` 5) | ✅ |
| 4 | Bucket na subida | `ArmazenamentoEvidenciasTest.shouldHaveBucketAfterStartupAndCreateItIdempotently` | `evidencias` existe sem ninguém criá-lo; rodar a rotina de novo não falha nem duplica | passou | ✅ |
| 5 | Gravar e ler | `shouldWriteAndReadBackTheSameBytes` (5 MB aleatórios) | bytes idênticos e tamanho anunciado igual ao lido | passou | ✅ |
| 6 | Listar | `shouldListKeysWithModificationDate` | só as chaves do prefixo, com `ultimaModificacao` não nula | passou | ✅ |
| 7 | Remover prefixo | `shouldRemoveOnlyTheGivenPrefix` | some tudo da evidência alvo; objeto de outra evidência do mesmo chamado fica | passou | ✅ |
| 8 | Prefixo vazio | `shouldRefuseToRemoveWithEmptyPrefix` | `IllegalArgumentException` | passou | ✅ |
| 9 | Formato das chaves | `unit/ChaveObjetoTest.shouldBuildKeysInTheDocumentedFormat` | exatamente os formatos do critério | passou | ✅ |
| 10 | Quebra: `miniatura.jpg` → `miniatura.png` | `-Dtest=ChaveObjetoTest` | falha | `ChaveObjetoTest...:21` falhou | ✅ |
| 11 | Quebra: `removerPrefixo` lista `""` em vez do prefixo | `-Dtest=ArmazenamentoEvidenciasTest` | falha | `shouldRemoveOnlyTheGivenPrefix:91` falhou (a outra evidência foi apagada) | ✅ |
| 12 | Quebra: sem o `@EventListener` do bucket | idem | falha | 4 testes falharam; `shouldHaveBucketAfterStartup...:46` e `NoSuchBucket` (404) nos de gravar/listar/remover | ✅ |
| 13 | Quebra: `gravar` com `tamanho / 2` | idem | falha | `shouldWriteAndReadBackTheSameBytes:61` falhou | ✅ |
| 14 | Quebra: `listar` devolve `lastModified` nulo | idem | falha | `shouldListKeysWithModificationDate:75` falhou | ✅ |
| 15 | Quebra: sem a guarda de prefixo vazio | idem | falha | `shouldRefuseToRemoveWithEmptyPrefix:96` falhou | ✅ |

## Observações

1. **Imagem:** o `MinIOContainer` do Testcontainers só aceita `minio/minio`; a imagem `pgsty/minio` do compose é declarada compatível com `asCompatibleSubstituteFor`.
2. **Tempo:** o `ArmazenamentoEvidenciasTest` leva cerca de 15 s, quase todos da subida do container do MinIO (um por JVM, partilhado por todos os contextos). O `BackendApplicationTests` (6) e o `OutboxTest` (5) passaram sem alteração de código.
3. **Limite do teste de idempotência:** `garantirBucket` engole e registra qualquer erro (decisão de subida), então o teste 4 não detectaria uma criação repetida que falhasse; ele prova que repetir não lança nem duplica o bucket. Não houve quebra proposital para esse trecho.
4. **Não verificado:** timeouts e a tradução para `ServicoIndisponivelException` (nenhum teste com MinIO parado ou lento); remoção de mais de 1000 objetos (lotes); a subida no ambiente dev com `make run-dev` (e as variáveis novas no `.env.dev`); a suíte completa com `TZ=UTC ./mvnw clean test`; o `OpenApiContratoTest` (nenhum endpoint mudou).

## Ajustes da revisão (2026-11-16)

Execução com `TZ=UTC`, branch `feat/evidencia`. Teste novo: `unit/ArmazenamentoRetentativaTest` (sem Docker).

| # | O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Gravar 400 KB via `ByteArrayInputStream` com falha 503 no 1º PUT | `shouldRetryTheWriteOfAByteArrayStreamLargerThan128KbAfterATransientFailure` | 2 PUTs; o 2º com o mesmo tamanho do 1º e o primeiro e o último bloco de 128 KB íntegros (o corpo vai em aws-chunked, então não é igual ao conteúdo) | passou | ✅ |
| 2 | Stream de mark limitado (`BufferedInputStream`) | `shouldNotRetryAStreamThatCannotBeResetBeyondTheMarkLimit` | erro `Resetting to invalid mark` e um único PUT (documenta o risco) | passou | ✅ |
| 3 | Experimento: `gravar(byte[])` com `fromBytes` e com `fromInputStream(ByteArrayInputStream)` (descartado) | teste 1 contra as duas versões | se a suspeita fosse real, a versão de stream falharia | as duas passaram: **suspeita não confirmada**, nada a corrigir | ✅ |
| 4 | Quebra do teste 1 | não há correção a desfazer; só quebraria se o chamador passasse stream não resetável | n/a | o teste 2 mostra esse caso falhando | n/a |
| 5 | `unit-test` | `make unit-test` | 0 falhas | 167 testes, 0 falhas, 0 erros | ✅ |

Não verificado: leitura do código/javadoc do SDK 2.59.1 (o comportamento foi observado por teste, não pelo fonte); retentativa contra o MinIO real.
