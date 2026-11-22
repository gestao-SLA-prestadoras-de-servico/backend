# [relatorio - 01] Consultar cumprimento de SLA

Data: 2026-11-21 · Branch: `feat/relatorio`

## Resultados

| O que | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Compilação | `./mvnw -q -DskipTests test-compile` | Compila | Compilou sem erros | ✅ |
| Testes da task | `TZ=UTC ./mvnw -Dtest=com.gestaosla.backend.integration.RelatorioSlaTest test` | Agregações, filtros e resultado vazio passam | 2 testes, 0 falhas | ✅ |
| Contrato OpenAPI | `TZ=UTC ./mvnw -Dtest=com.gestaosla.backend.integration.OpenApiContratoTest test` | `docs/openapi.json` em dia | 3 testes, 0 falhas; o arquivo foi regenerado com o endpoint e os schemas do relatório | ✅ |
| Suíte completa | `TZ=UTC ./mvnw clean test` | Todos passam | 516 testes: 1 falha do `OpenApiContratoTest` (regenerou `docs/openapi.json`, depois passou isolado) e 2 erros de `CadastroUsuarioTest`/`InativacaoUsuarioTest` por falha ao subir o container Keycloak 26.4 | ⚠️ |

## Observações

- Durante a execução apareceram dois defeitos no código do relatório, corrigidos na causa:
  - `RelatorioSlaTest` acessava o `RelatorioSlaService` package-private de outro pacote; a solução foi o helper público `RelatorioSlaTestHelper`, no pacote `internal`, seguindo o padrão de `ChamadoTestHelper`.
  - O SQL passava `Instant` ao driver PostgreSQL, que não infere o tipo, e usava o literal `null` em `GROUP BY`/`ORDER BY`, rejeitado pelo PostgreSQL. As datas agora vão como `OffsetDateTime` em UTC e o agrupamento/ordenação usa só as colunas efetivamente agrupadas.
- A falha do `OpenApiContratoTest` na suíte era esperada: ele regenera `docs/openapi.json` quando o contrato muda. O diff contém apenas o endpoint `/api/relatorios/sla` e os schemas `RelatorioSlaResposta`, `RelogioMetricas`, `GrupoCliente`, `GrupoTecnico` e `Metricas`.
- O filtro e a quebra por técnico usam `tecnicoSub` (`usuario.sub`), alinhado ao `?tecnicoSub=` da consulta de chamados; `clienteId` continua sendo o `cliente.id`.
- Os 2 erros de Keycloak são de infraestrutura do ambiente: o container não ficou acessível no tempo limite. Repetidos isoladamente, falharam de novo. Não têm relação com o relatório.
- A verificação HTTP com token real e o ambiente dev não foram executados.
