# Testes — [cadastro - 04] Vincular política ao contrato

> Executados em 10/11/2026, na branch `cadastro_2_3_4` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 208 testes, 0 falhas (`PoliticaContratoTest` 8 e os 200 anteriores). Na rodada anterior, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Vincular e consultar | `PoliticaContratoTest.shouldLinkPolicyAndExposeItsCurrentVersion` | 200 com `politicaId`; consulta devolve a versão da `Padrão` com os prazos da seção 5.4 | passou | ✅ |
| 3 | Trocar só afeta o depois (RN-02, RN-05) | `shouldReplacePolicyAffectingOnlyLaterLookups` | uma só `politica_id` no contrato; consulta devolve a nova; a versão obtida antes continua idêntica no banco | passou | ✅ |
| 4 | Versão nova da política vinculada | `shouldFollowNewVersionOfLinkedPolicy` | consulta passa a devolver a versão 2, sem revincular | passou | ✅ |
| 5 | Auditoria da troca | `shouldAuditEachChangeButNotRelinkingSamePolicy` (Padrão, Padrão de novo, Ouro) | 2 registros: nula → Padrão e Padrão → Ouro, com sub e perfil do gestor | passou | ✅ |
| 6 | Contrato sem política (RN-03) | `shouldRejectLookupOfContractWithoutPolicy` | `CONTRATO_SEM_POLITICA`; contrato inexistente → `RecursoNaoEncontradoException` | passou | ✅ |
| 7 | Erros do PUT | `shouldReturn404ForUnknownContractAnd422ForUnknownPolicy` | 404; 422 `POLITICA_INEXISTENTE`; 400 com `politicaId` ausente | passou | ✅ |
| 8 | Listagem mostra a política | `shouldShowPolicyInContractListing` | `politicaId` no item | passou | ✅ |
| 9 | Papéis | `shouldAllowOnlyManagerToLinkPolicy` e `AcessoPorRotaTest` | 403 para técnico e cliente, sem gravar; 403 antes de validar o corpo | passou | ✅ |
| 10 | Quebra: sem auditoria | cópia, `-Dtest=PoliticaContratoTest` | falha | teste 5 falhou | ✅ |
| 11 | Quebra: consulta devolve a primeira versão | idem | falha | teste 4 falhou | ✅ |
| 12 | Quebra: audita também o revínculo da mesma política | idem | falha | teste 5 falhou | ✅ |
| 13 | Quebra: sem checar política inexistente | idem | falha | teste 7: 422 esperado, veio 500 (FK) | ✅ |
| 14 | Quebra: contrato sem política cai na `Padrão` | idem | falha | teste 6 falhou | ✅ |
| 15 | Dev: migrações V7 a V9 | stack de dev completa a partir da cópia (`-p flowsla-verificacao`, volumes próprios); `flyway_schema_history` e `psql` | V7, V8 e V9 com sucesso; seed `Padrão` 120/540/1080 e 540/1620/2700, `NORMAL`; `contrato.politica_id` uuid nulável; `UPDATE` na versão rejeitado | todos conforme o esperado | ✅ |

## Observações

1. **Observação de ambiente:** na cópia, a pasta `infra/rabbitmq` foi reetiquetada com `chcon -t container_file_t`, pelo mesmo motivo do achado 4 da outbox-03. O ambiente de verificação foi derrubado com `down -v` apenas no projeto `flowsla-verificacao`; os volumes `infra_*` de dev não foram tocados.
2. **Não verificado:** os endpoints no dev com token real (o mesmo limite das cadastro-02 e 03). O roteiro está na seção 4C do `docs/plano-testes-manuais.md`.
3. **Não verificado:** a trava do contrato em trocas simultâneas. Não há teste concorrente; ela só afeta o `politicaAnterior` da auditoria, não o vínculo.
4. **Pendente para o módulo chamado:** guardar o `versaoId` devolvido por `ContratoConsulta.versaoVigenteDaPolitica` na abertura. RN-05 só fica completa com isso.
