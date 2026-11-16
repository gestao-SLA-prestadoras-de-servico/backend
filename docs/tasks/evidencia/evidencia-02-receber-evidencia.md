# [evidência - 02] Receber evidência: validar, sanitizar e gravar

> Requisitos: RF-24, RF-25, RN-74 a RN-77, RN-83, RN-85, RN-112, RN-113, RN-121 · Depende de: [evidência - 01], [chamado - 02], [chamado - 10], [outbox - 01], [auditoria - 01]

## Contexto

O técnico atribuído anexa fotos do atendimento. A recepção confere quem envia e em que estado está o chamado, valida o arquivo pelos bytes, remove todo metadado (aplicando antes a rotação do EXIF aos pixels), calcula o hash, grava a imagem no MinIO e só então abre a transação que cria a evidência `RECEBIDA`, a auditoria e o evento de processamento. A resposta é imediata (`202`); os derivados (miniatura e visualização) são gerados depois, em outra task.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /api/chamados/{id}/evidencias` só aceita o técnico atribuído, com o chamado em EM_ATENDIMENTO e atendimento EM_ANDAMENTO | `EvidenciaRegrasAcesso` e `@PreAuthorize` (TECNICO). `RecepcaoEvidenciaService` confere `AcessoChamado.podeAcessar` (técnico não atribuído ou chamado inexistente: 404, RN-126) e `AtendimentoConsulta.correnteDe` com estado EM_ANDAMENTO (senão 422 `TRANSICAO_INVALIDA`). Atendimento EM_ANDAMENTO implica chamado EM_ATENDIMENTO (ver Decisões). |
| Foto de celular com GPS e orientação 6 é armazenada em pé e sem nenhum EXIF | `SanitizadorImagem`: lê a orientação com o metadata-extractor, aplica a transformação aos pixels e regrava do zero com o ImageIO. Provado lendo o objeto no MinIO. |
| PDF renomeado para `.jpg` é rejeitado com 422 | O tipo vem da assinatura dos bytes; nome e Content-Type são ignorados. `FORMATO_NAO_SUPORTADO`. |
| Arquivo acima de 10 MB é rejeitado e nada vai para o storage | `spring.servlet.multipart.max-file-size=10MB` (413 `ARQUIVO_MUITO_GRANDE`, antes do serviço) e a mesma conferência no serviço, como defesa. |
| Imagem acima do teto de megapixels é rejeitada antes de decodificar | Dimensões lidas do cabeçalho (`ImageReader.getWidth/getHeight`, sem `read()`); acima de `app.evidencia.teto-megapixels` (40), 422 `IMAGEM_MUITO_GRANDE`. |
| Resposta 202 com o id e o estado RECEBIDA | `{"id": "...", "estado": "RECEBIDA"}`. |
| Falha no banco deixa objeto órfão | O objeto é gravado antes da transação; se ela falhar, o objeto fica e nada fica no banco (nenhuma linha, auditoria ou evento). A remoção do órfão é da futura [retenção - 04]. |
| Testes de integração criados e passando | `RecepcaoEvidenciaTest` (16), `RecepcaoEvidenciaCorridaTest` (1) e `SanitizadorImagemTest` (15, unitário). |

## Passos técnicos realizados

- [x] **Limite de multipart**: `max-file-size=10MB` e `max-request-size=11MB`. O estouro (`MaxUploadSizeExceededException`) sai como 413 com `ARQUIVO_MUITO_GRANDE` pelo `GlobalExceptionHandler`.
- [x] **Formato pela assinatura** (`SanitizadorImagem`): JPEG (`FF D8 FF`), PNG (`89 50 4E 47 0D 0A 1A 0A`) e WebP (`RIFF`, 4 bytes, `WEBP`). Qualquer outra coisa: 422 `FORMATO_NAO_SUPORTADO`. Assinatura válida mas imagem que não decodifica: 422 `IMAGEM_INVALIDA`.
- [x] **Teto de dimensões**: `app.evidencia.teto-megapixels=40`, lido do cabeçalho antes de qualquer decodificação.
- [x] **Orientação EXIF**: `ExifIFD0Directory.TAG_ORIENTATION` (1 a 8), com as oito transformações (rotações e espelhamentos) aplicadas aos pixels por `AffineTransform`, sem perda.
- [x] **Regravação sem metadados**: JPEG e WebP saem em JPEG (qualidade 0,92); PNG sai em PNG (com alfa, se tinha). O tipo e a extensão gravados são os do arquivo final.
- [x] **SHA-256** (hex minúsculo, 64 caracteres) sobre os bytes sanitizados.
- [x] **Gravação** (`RecepcaoEvidenciaService`): objeto no MinIO (`ChaveObjeto.original(ext)`) fora de transação e, depois, uma transação com a evidência, a auditoria `ENVIO_EVIDENCIA` e o evento `PROCESSAR_EVIDENCIA` (exchange `evidencias`, routing key `processamento`, payload só com `evidenciaId`, `chamadoId` e `chaveOriginal`).
- [x] **Migração V15** (`evidencia`): ver Decisões.
- [x] **Porta do chamado**: `ContagemEvidencias` implementa `chamado.api.EvidenciasDoChamado`; o detalhe do chamado passa a mostrar a contagem por atendimento.
- [x] **OpenAPI**: `docs/openapi.json` com o endpoint e o componente `ArquivoMuitoGrande`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Rota | `POST /api/chamados/{id}/evidencias`, multipart, campo `arquivo`, papel TECNICO (`EvidenciaRegrasAcesso` + `@PreAuthorize`) | Padrão do projeto (prefixo `/api`; as duas barreiras). O matcher `GET /api/chamados/**` do chamado só vale para GET, então o POST cai na regra do evidencia. |
| Acesso e estado | Técnico não atribuído ou chamado inexistente: 404. Sem atendimento corrente, PENDENTE ou chamado fora de EM_ATENDIMENTO: 422 `TRANSICAO_INVALIDA` | RN-126 e mesma decisão da chamado-10 (reaproveitar o código). |
| "Atendimento EM_ANDAMENTO" no lugar de "chamado EM_ATENDIMENTO" | Só se confere o atendimento, sem acrescentar nada na porta do chamado | O início muda os dois juntos (`AtendimentoService.iniciar`), e reatribuição e cancelamento interrompem o atendimento. **Pendência da chamado-11 (resolução):** ao tirar o chamado de EM_ATENDIMENTO, o atendimento precisa sair de EM_ANDAMENTO (concluído), senão esta conferência passa a aceitar evidência em chamado resolvido. |
| Ordem das conferências | Acesso e estado antes de ler o arquivo; refeitos dentro da transação | Quem não pode enviar não gasta CPU. A refeita cobre a corrida com reatribuição ou cancelamento; se falhar ali, o objeto fica órfão (aceitável, RN-112). |
| 413 | `ARQUIVO_MUITO_GRANDE`: o limite do multipart lança `MaxUploadSizeExceededException`; o serviço lança a mesma exceção como defesa | Um só caminho de erro. O `GlobalExceptionHandler` mapeia o 413 (único da API) para o código. Nada é gravado. |
| Teto de megapixels | 40 MP, em `app.evidencia.teto-megapixels` | 40 MP decodificados ocupam cerca de 160 MB de heap (4 bytes por pixel): é o botão a calibrar com o heap e a concorrência da API. |
| WebP | Aceito e gravado como **JPEG** (qualidade 0,92, fundo branco onde havia transparência) | Decisão do usuário: o TwelveMonkeys só lê WebP, não grava. O PNG continua PNG, com alfa. |
| Perfil de cor | A regravação descarta também o perfil ICC | Sem metadado nenhum, por RN-76; fotos em espaço de cor largo podem mudar levemente de tom. |
| EXIF ilegível | A foto não é rejeitada; fica sem rotação | O EXIF só orienta; a imagem em si é válida. |
| Id da evidência | UUID v7 gerado antes (`UuidVersion7Strategy` do Hibernate), e a entidade **não estende** `EntidadeBase` | A chave do objeto leva o id e o objeto é gravado antes da transação (RN-112). O gerador da `EntidadeBase` sobrescreveria um id atribuído. A entidade segue a convenção nos outros pontos (UUID v7, `created_at`, `updated_at`) e usa `@Version` com wrapper (`Long`), para o `save` fazer `persist` sem `select`. |
| `chamado_id` na tabela | Coluna `chamado_id` (FK) além de `atendimento_id` | RN-104 e a chave do objeto usam o chamado; evita juntar com o atendimento. |
| Imutabilidade do hash (RN-77) | Trigger que rejeita `UPDATE` de `hash_sha256` e `chave_original`, e colunas `updatable = false` na entidade | A linha muda com o processamento (estado, chaves dos derivados), então a regra das tabelas imutáveis (trigger em tudo) não serve; a trigger protege só os dois campos, também contra SQL direto. |
| Nome do arquivo | Guardado só como metadado em `nome` (sem path, até 255 caracteres); fora da auditoria e da chave | Pode conter dado pessoal (RN-122). |
| Auditoria | `ENVIO_EVIDENCIA` / `EVIDENCIA`, com `tipoConteudo`, `tamanho` e `hashSha256` | Sem dado pessoal. |
| Porta | `chamado.api.EvidenciasDoChamado` implementada em `ContagemEvidencias`. **Não** foi criada a `ContadorEvidencias` citada no texto da task | A porta já existe e o chamado já a injeta como `Optional`. A [chamado - 11] ainda não existe; se ela quiser outro nome ou formato, é lá que se ajusta. A contagem inclui todos os estados (inclusive `EXPURGADA`); o expurgo decide se isso muda. |

## Atenção ao atualizar um ambiente existente

- **Migração V15** (`evidencia`); o Flyway roda na subida.
- Nenhuma variável de ambiente nova: `spring.servlet.multipart.*` e `app.evidencia.teto-megapixels` têm valor fixo no `application.properties`.
- **Eventos `PROCESSAR_EVIDENCIA`:** a exchange `evidencias` e a fila `evidencias.processamento` (routing key `processamento`) já existem na topologia (`infra/rabbitmq`). Não há consumidor ainda (task futura): as mensagens ficam na fila `evidencias.processamento` até ele existir. O payload não segue um schema de `contratos/` (esses são só das notificações): são três referências (`evidenciaId`, `chamadoId`, `chaveOriginal`) mais o `eventoId`.
- **Objetos órfãos** (transação que falhou depois da gravação) ficam no bucket até a [retenção - 04] existir; ela ainda não existe.

## Como verificar

Ver `docs/testes/evidencia/evidencia-02-receber-evidencia.md` e a seção 7G do `docs/plano-testes-manuais.md`.
