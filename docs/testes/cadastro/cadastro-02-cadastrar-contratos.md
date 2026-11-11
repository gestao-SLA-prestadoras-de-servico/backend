# Testes — [cadastro - 02] Cadastrar contratos

> Executados em 10/11/2026, na branch `cadastro_2_3_4`, criada a partir de `feat/outbox` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 180 testes, 0 falhas (`ContratoTest` 9, `ContratoVigenciaTest` 5 e os 166 anteriores). Na primeira rodada, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Vigência inclusiva (unitário) | `ContratoVigenciaTest`: primeiro, meio e último dia; véspera e dia seguinte; contrato de um dia | vigente do início ao fim, inclusive | passou | ✅ |
| 3 | Validação do corpo (unitário) | `ContratoVigenciaTest`: fim antes do início; campos ausentes | só `fimVigencia`; os 4 campos | passou | ✅ |
| 4 | Criar | `ContratoTest.shouldCreateContract` | 201 com cliente e datas | passou | ✅ |
| 5 | Fim antes do início | `shouldReturn400WhenEndIsBeforeStart` | 400 `VALIDACAO`, `erros[0].campo = fimVigencia` | passou | ✅ |
| 6 | Código repetido | `shouldReturn409ForDuplicateCode` | 409 `CODIGO_CONTRATO_JA_EXISTE` | passou | ✅ |
| 7 | Cliente inexistente e inativo | `shouldReturn422ForUnknownOrInactiveClient` | 422 `CLIENTE_INEXISTENTE` e `CLIENTE_INATIVO` | passou | ✅ |
| 8 | Lista com filtros (RN-06) | `shouldListFilteringByClientAndInForce`: 4 contratos de um cliente (vigente, de um dia só hoje, vencido ontem, começando amanhã) e 1 de outro | por cliente, os 4; `vigente=true`, os 2 vigentes; `vigente=false`, o vencido e o futuro | passou | ✅ |
| 9 | `/me/contratos` | `shouldShowClientOnlyInForceContractsOfOwnCompany`, com `?clienteId=` de outro cliente na URL | só o vigente da própria empresa; parâmetro ignorado | passou | ✅ |
| 10 | Último dia no fuso do sistema | `shouldStayInForceUntilEndOfLastDayInSystemTimeZone` (`RelogioMutavel`) | às 23:59:59 do fim, vigente; à 00:00:00 do dia seguinte, fora | passou | ✅ |
| 11 | Papéis | `shouldAllowOnlyManagerOnContractsAndOnlyClientOnMyContracts` e `AcessoPorRotaTest` (rotas novas) | 403 para técnico e cliente em `/contratos`; 403 para técnico e gestor em `/me/contratos`; 403 antes de validar o corpo | passou | ✅ |
| 12 | CHECK no banco | `shouldRejectEndBeforeStartInDatabase` (insert direto) | violação de `contrato_vigencia_ck` | passou | ✅ |
| 13 | Quebra: fim exclusivo em `estaVigenteEm` | cópia, `-Dtest=ContratoVigenciaTest` | falha | `shouldBeInForceFromFirstToLastDayInclusive` e `shouldAcceptSingleDayContract` falharam | ✅ |
| 14 | Quebra: fim exclusivo no filtro SQL (`>` no lugar de `>=`) | cópia, `-Dtest=ContratoTest` | falha | testes 8 e 10 falharam | ✅ |
| 15 | Quebra: `/me` sem o filtro da empresa | idem | falha | testes 9 e 10 falharam | ✅ |
| 16 | Quebra: sem checar cliente inativo | idem | falha | teste 7: esperado 422, veio 201 | ✅ |
| 17 | Quebra: `ContratoController` importando `UsuarioCorrente` | cópia, `-Dtest=ModularidadeTest` | falha por ciclo | `Cycle detected: Slice cadastro -> ...` | ✅ |

## Observações

1. **Achado:** usar `UsuarioCorrente` no cadastro cria ciclo entre os módulos (`identidade` → `cadastro.api` → `identidade.api`), como mostra o item 17. A empresa é lida com `@AuthenticationPrincipal(expression = "empresaClienteId")`, e o padrão foi registrado no README.
2. **Observação:** o `OpenApiContratoTest` regenerou o `docs/openapi.json`. Além das rotas novas, o springdoc renumerou os `operationId` repetidos (`listar_1` virou `listar_2`, por exemplo). O frontend não deve depender desses ids.
3. **Não verificado no dev:** os endpoints com token real. Nenhum client do realm permite login direto, então o token só sai pelo navegador. O roteiro está na seção 4A e no item 7.5a do `docs/plano-testes-manuais.md`.
