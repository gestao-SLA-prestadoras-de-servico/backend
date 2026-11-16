# Testes — [outbox - 04] Definir os contratos das mensagens de notificação

> Executados em 09/11/2026, na branch `feat/outbox` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 166 testes, 0 falhas (`ContratoNotificacaoTest` 15 e os 151 anteriores; `ModularidadeTest` passa com o `outbox` dependendo de `cadastro.api` e `identidade.api`) | ✅ |
| 2 | Schemas versionados em dia | `ContratoNotificacaoTest.shouldMatchVersionedSchemas` | gerado = arquivo | passou (na primeira execução, gerou os 8 arquivos e falhou, como previsto) | ✅ |
| 3 | Payload gravado valida contra o schema, para cada tipo | `shouldStorePayloadThatMatchesVersionedSchema` (8 execuções) | 0 erros; `tipo`, exchange `notificacoes`, routing key do canal e `eventoId` corretos | passou | ✅ |
| 4 | Campo a mais é recusado | `shouldRejectPayloadWithExtraFieldsLikeContractOrEvidenceData` (`contratoId` e `evidenciaUrl`) | 2 erros | passou | ✅ |
| 5 | Violação sem `relogio` e aviso com data inválida | `shouldRejectViolationWithoutClockAndNoticeWithoutClosingDate` | erros nos dois | passou | ✅ |
| 6 | Nenhum schema declara contrato ou evidência | `shouldNeverDeclareContractOrEvidenceFieldsInAnySchema` | sem `contrato` nem `evidencia` | passou | ✅ |
| 7 | Destinatário tipado pelo canal | `shouldTypeRecipientsByChannel` | interno com `DestinatariosInternos`, e-mail com `DestinatarioEmail` | passou | ✅ |
| 8 | Montador recusa cliente, sub desconhecido e lista vazia; remove repetidos | `shouldRejectClientAndUnknownSubsAsInternalRecipients` | `IllegalArgumentException` com o sub recusado | passou | ✅ |
| 9 | E-mail só do contato do cliente | `shouldSendEmailOnlyToClientContactAddress` | `contato@boamassa.com.br` | passou | ✅ |
| 10 | Quebra: montador aceita CLIENTE | cópia, `-Dtest=ContratoNotificacaoTest` | o teste 8 falha | falhou | ✅ |
| 11 | Quebra: `Resolucao` com `contratoId` | idem (com o teste ajustado para compilar) | falha | `shouldMatchVersionedSchemas`, `shouldStorePayloadThatMatchesVersionedSchema` e `shouldRejectPayloadWithExtraFields...` falharam | ✅ |
| 12 | Quebra: VIOLACAO no canal de e-mail | idem | o teste 7 falha | falhou em `[VIOLACAO]` | ✅ |
| 13 | Quebra: `@JsonIgnore` no `titulo` da atribuição | idem | o schema muda e o payload deixa de validar | `shouldMatchVersionedSchemas` e `shouldStorePayloadThatMatchesVersionedSchema` falharam | ✅ |
| 14 | `format` conferido pelo validador | primeira execução, sem `formatAssertionsEnabled` | data "amanhã" recusada | **aceita**: o teste 5 falhou; corrigido ligando a opção | ✅ |

## Observações

1. **Achado:** no draft 2020-12, `format` é só anotação por padrão. Sem `formatAssertionsEnabled(true)`, o networknt aceitava `"amanhã"` como `date-time` (item 14). O validador do ms-notificacoes precisa da mesma configuração, ou valida menos que o nosso teste.
2. **Achado:** a primeira tentativa da quebra do item 11 não compilou (o teste usa o construtor do record), o que também barra a mudança, mas não mostra qual teste pega. Refeita com o teste ajustado.
3. **Achado:** o `JacksonModule` do victools está deprecated na 5.0.0; o teste usa o `JacksonSchemaModule`, e os schemas gerados ficaram iguais.
4. **Não verificado:** o consumo das mensagens pelo ms-notificacoes com estes schemas. A combinação dos campos com a equipe foi informada pelo usuário.
5. **Não verificado no dev:** nenhum código grava notificação ainda; não há o que exercitar à mão. O plano de testes manuais não mudou.
