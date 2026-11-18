# [evidência - 01] Criar o adaptador do object storage (MinIO)

> Requisitos: RN-80, RN-102 · Depende de: [plataforma - 02] (MinIO no compose)

## Contexto

As evidências (arquivos e derivados) ficam no MinIO, não no banco. Esta task cria o único ponto de acesso a ele dentro do módulo `evidencia`, para as próximas tasks (upload, download, remoção) não falarem com o SDK diretamente.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| O bucket `evidencias` é criado na subida se não existir | `ArmazenamentoEvidencias.garantirBucket` (`ApplicationReadyEvent`): `headBucket` e, em `NoSuchBucket`, `createBucket`. Repetir a rotina não falha. |
| Grava (stream), lê (stream), lista com data de modificação e remove um prefixo inteiro | `gravar(chave, InputStream, tamanho, contentType)`, `ler(chave)` (devolve `ObjetoLido`: stream + tamanho para o Content-Length), `listar(prefixo)` (`ObjetoArmazenado`: chave, `ultimaModificacao`, tamanho; paginado) e `removerPrefixo` (lista e apaga em lotes de 1000). |
| Chaves `evidencias/{chamadoId}/{evidenciaId}/original.{ext}`, `miniatura.jpg`, `visualizacao.jpg` | `ChaveObjeto` (`prefixo()`, `original(ext)`, `miniatura()`, `visualizacao()`). |
| Credenciais por variáveis de ambiente | `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` nos três `.envs/.env.*.example`, lidas em `app.armazenamento.*`. |
| Testes de integração com MinIO (Testcontainers) criados e passando | `ArmazenamentoEvidenciasTest` (5) e `ChaveObjetoTest` (1, unitário). |

## Passos técnicos realizados

- [x] **Cliente S3**: AWS SDK v2 (`s3` e `url-connection-client`, sem Netty nem Apache; os dois clientes que o `s3` traz por padrão foram excluídos) com o BOM da AWS em `dependencyManagement` (`awssdk.version`). `forcePathStyle(true)` e região fixa `us-east-1`.
- [x] **`ArmazenamentoEvidencias`** e **`ChaveObjeto`** em `evidencia/internal`, sem interface; `ArmazenamentoConfig` e `ArmazenamentoProperties` montam o `S3Client`.
- [x] **Timeouts**: `connect-timeout=2s`, `read-timeout=30s` (socket) e `api-call-timeout=60s`, comportando 10 MB.
- [x] **Erros**: `SdkClientException` (rede, timeout) e `S3Exception` 5xx viram `ServicoIndisponivelException` (503 `SERVICO_INDISPONIVEL`, já tratada no `GlobalExceptionHandler`). Erros 4xx (ex.: chave inexistente) passam como vieram.
- [x] **Teste**: `MinioTeste` (um container por JVM) registrado no `TestcontainersConfiguration`, para todo contexto ter storage válido.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Cliente | AWS SDK v2 com `url-connection-client` | Decisão do usuário; HTTP leve, sem Netty. |
| MinIO fora do ar na subida | Só `warn` no log | O compose já espera o healthcheck do MinIO; a API não deve depender do storage para ligar. O uso falha com 503. |
| `ler` | Devolve `ObjetoLido(stream, tamanho)` | Uma só chamada traz o tamanho para o Content-Length da task 04, sem `headObject` extra. |
| Prefixo vazio em `removerPrefixo` | Recusado (`IllegalArgumentException`) | Prefixo vazio apagaria o bucket inteiro. |
| Remoção parcial | `deleteObjects` com erro vira `ServicoIndisponivelException` | A remoção é idempotente; quem chama pode repetir. |
| Credenciais | Usuário root do MinIO, por ora | Usuário dedicado é pendência registrada. |
| Nome do bucket | Constante `ArmazenamentoEvidencias.BUCKET` | Valor que nunca muda não vira configuração. As chaves também começam com `evidencias/`, como o critério pede. |
| Retentativas | Padrão do SDK (3 tentativas) | Não foi pedido ajuste; o `api-call-timeout` limita o total. |

## Atenção ao atualizar um ambiente existente

- Três variáveis novas: `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY` e `MINIO_SECRET_KEY`. Copie-as do `.env.*.example` para o `.env.*` local; sem elas a aplicação não sobe (placeholder não resolvido). Em `MINIO_ACCESS_KEY` e `MINIO_SECRET_KEY` repita `MINIO_ROOT_USER` e `MINIO_ROOT_PASSWORD`.
- Nenhuma migração.

## Como verificar

Ver `docs/testes/evidencia/evidencia-01-adaptador-object-storage.md`.

## Ajustes da revisão (2026-11-16)

**C. Retentativa do SDK com stream.** Suspeita: `RequestBody.fromInputStream(conteudo, tamanho)` só deixa o SDK repetir o PUT (3 tentativas) se o stream puder ser resetado, o que vale até o limite de mark/reset (128 KB); gravações maiores não seriam repetidas numa falha transitória. Observado (servidor HTTP local que responde 503 ao 1º PUT e 200 ao 2º, conteúdo de 400 KB, mesma configuração do `ArmazenamentoConfig`): com `ByteArrayInputStream`, que é o que os dois chamadores de produção (`RecepcaoEvidenciaService`, `GeradorDerivados`) passam, **a retentativa funciona**: o `ByteArrayInputStream` reseta sem limite (ignora o readlimit) e a 2ª tentativa chega íntegra. Com um stream de mark limitado (`BufferedInputStream`), o SDK falha com `Resetting to invalid mark` e não repete. Decisão: **código inalterado** (a correção só era para ser feita se a suspeita se confirmasse para os chamadores atuais; não se confirmou). O método de stream segue sendo o único de gravação e o critério de aceite. Risco que fica: um chamador futuro que passe um stream não resetável acima de 128 KB perde a retentativa; nesse caso, ler para `byte[]` e usar `RequestBody.fromBytes`.
