# Testes — [plataforma - 03] Definir o padrão de schema do banco

> Executados em 20/10/2026, na branch `feat/plataforma` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`).

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Testes unitários continuam passando | `make unit-test` | `ModularidadeTest` passa (`EntidadeBase` em `shared.api`) | `Tests run: 2, Failures: 0` — BUILD SUCCESS | ✅ |
| 2 | Flyway aplica a baseline | `make integration-test` (log) | `V1 - baseline` aplicada | `Successfully applied 1 migration ... now at version v1` | ✅ |
| 3 | Sequência existe | `shouldCreateChamadoNumeroSequence` | `nextval('chamado_numero_seq')` retorna número | passou | ✅ |
| 4 | Nenhuma coluna de data/hora sem fuso | `shouldStoreEveryDateTimeAsTimestamptz` | lista vazia | passou | ✅ |
| 5 | Teste 4 pega `timestamp` sem fuso | Migração temporária com `created_at timestamp` | teste falha | `Expecting empty but was: ["tmp.created_at"]` | ✅ |
| 6 | `validate` derruba a aplicação se faltar coluna | Entidade temporária com `@Version` e tabela sem `version` | contexto não sobe | `Schema validation: missing column [version] in table [tmp]` | ✅ |
| 7 | `validate` aceita entidade conforme o padrão | Entidade temporária estendendo `EntidadeBase`, enum `STRING`, `@Version`, tabela com `uuid`/`timestamptz`/`varchar`/`bigint` | contexto sobe | subiu | ✅ |
| 8 | Padrão funciona ao gravar | Dois `saveAndFlush` da entidade temporária | UUID v7, `numero` vindo da sequência, `created_at` preenchido, `version` 0 | `id=01a15ab8-…` (versão 7), `numero=1,2`, `created=2026-10-20T19:10:07Z`, `version=0` | ✅ |

Entidade, repositório, migração e teste temporários dos itens 5 a 8 foram removidos após a verificação.

## Observações

1. **Achado:** o `ddl-auto=validate` **não** acusa coluna `timestamp` sem fuso mapeada como `Instant` (item 5, antes do teste novo, a aplicação subia). Por isso o teste 4 existe.
2. **Não verificado:** subir o ambiente dev via `make run-dev` com o Flyway. Os testes usam o mesmo PostgreSQL 17 via Testcontainers.
