# Testes — [cadastro - 03] Cadastrar políticas de SLA versionadas

> Executados em 10/11/2026, na branch `cadastro_2_3_4` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 200 testes, 0 falhas (`PoliticaTest` 14, `OrdemPrazosTest` 6 e os 180 anteriores). Na rodada anterior, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Ordem dos prazos (unitário) | `OrdemPrazosTest`: matriz padrão; resposta = campo, resposta > campo, campo = resolução, campo > resolução e tudo invertido, em ALTA e em NORMAL | matriz aceita; cada violação lança `ORDEM_PRAZOS_INVALIDA` citando a prioridade | passou | ✅ |
| 3 | Seed da seção 5.4 | `PoliticaTest.shouldSeedDefaultPolicy` | `Padrão`, v1: 120/540/1080 e 540/1620/2700, padrão `NORMAL` | passou | ✅ |
| 4 | Criar com versão 1 e auditar | `shouldCreatePolicyWithFirstVersionAndAudit` | 201, `versaoAtual.numero = 1`, `criadaPor` = sub do gestor; 1 auditoria `ALTERACAO_POLITICA` com `versao: 1` | passou | ✅ |
| 5 | Nova versão sem mudar a anterior | `shouldCreateNewVersionKeepingPreviousOneUnchanged` (linha da v1 comparada coluna a coluna antes e depois) | 201 `numero: 2`; v1 idêntica; histórico `[2, 1]`; auditorias das versões 1 e 2 | passou | ✅ |
| 6 | Versões simultâneas | `shouldNumberConcurrentVersionsSequentially` (5 threads) | 5 × 201; números 1 a 6 sem repetição | passou | ✅ |
| 7 | Listagem com a versão atual | `shouldListPoliciesWithCurrentVersion` | a política nova com `versaoAtual.numero = 2`; `Padrão` presente | passou | ✅ |
| 8 | 422 por ordem inválida | `shouldReturn422ForDeadlinesOutOfOrder` (4 casos: igualdade e inversão, em ALTA e em NORMAL; no POST da política e no de versão) | 422 `ORDEM_PRAZOS_INVALIDA`; nada gravado | passou | ✅ |
| 9 | Prazo ausente ou zero | `shouldReturn400ForMissingOrNonPositiveDeadline` | 400 `VALIDACAO` em `prazos.alta.resposta` e `prazos.normal` | passou | ✅ |
| 10 | Nome repetido | `shouldReturn409ForDuplicateName` | 409 `NOME_POLITICA_JA_EXISTE` | passou | ✅ |
| 11 | Política inexistente | `shouldReturn404ForUnknownPolicy` | 404 na versão nova e no histórico | passou | ✅ |
| 12 | Imutável no banco | `shouldRejectUpdateAndDeleteOfVersionInDatabase` | `UPDATE`, `DELETE` e `TRUNCATE` rejeitados pelo trigger | passou | ✅ |
| 13 | Papéis | `shouldAllowOnlyManager` e `AcessoPorRotaTest` (4 rotas novas) | 403 para técnico e cliente, antes de validar o corpo | passou | ✅ |
| 14 | Quebra: `<=` no lugar de `<` em `respeitaOrdem` | cópia, `-Dtest=OrdemPrazosTest,PoliticaTest` | falha | unitário falhou (casos de igualdade); integração: 422 esperado, veio 500 (o CHECK do banco barrou) | ✅ |
| 15 | Quebra: sem auditoria | cópia, `-Dtest=PoliticaTest` | falha | testes 4 e 5 falharam | ✅ |
| 16 | Quebra: migração sem os triggers | idem | falha | teste 12 falhou | ✅ |
| 17 | Quebra: seed com padrão `ALTA` | idem | falha | teste 3: esperado `NORMAL`, veio `ALTA` | ✅ |
| 18 | Quebra: versão nova sempre com número 1 | idem | falha | testes 5 (500 pela UNIQUE) e 7 falharam | ✅ |
| 19 | Quebra: sem a trava `PESSIMISTIC_WRITE` | cópia, `-Dtest=PoliticaTest#shouldNumberConcurrentVersionsSequentially`, 3 rodadas | falha | falhou nas 3 rodadas | ✅ |

## Observações

1. **Achado:** na quebra do item 14, o CHECK `politica_sla_versao_ordem_ck` impediu que a política inválida fosse gravada, mas a resposta foi 500 em vez de 422. O CHECK protege o dado; o código certo depende da validação na aplicação.
2. **Achado:** sem a trava na política, versões simultâneas calculavam o mesmo número, e a UNIQUE `(politica_id, numero)` transformava a segunda em 500 (item 19).
3. **Não verificado no dev:** os endpoints com token real (o mesmo limite da cadastro-02). O roteiro está na seção 4B do `docs/plano-testes-manuais.md`. A `V8` e a seed rodaram no Postgres 17 do Testcontainers, a mesma imagem do dev.
