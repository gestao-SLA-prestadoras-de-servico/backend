# Testes — [chamado - 04] Consultar chamados com isolamento por perfil

> Executados em 14/11/2026, na branch `feat/chamados` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 360 testes, 0 falhas (ver [chamado - 03]) | ✅ |
| 2 | Filtros do gestor | `ConsultaChamadoTest.shouldFilterListingForManager` (3 chamados da empresa: aberto NORMAL, atribuído com ALTA definida, em atendimento violado) | cada filtro (estado, cliente, técnico, prioridade efetiva ALTA/NORMAL, com violação true/false) devolve só os chamados certos | passou | ✅ |
| 3 | Ordenação | `shouldSortByNumberAndByNearestDeadline` | padrão: número decrescente; `proximoVencimento,asc`: o de prazo mais próximo primeiro, o sem prazo correndo por último | passou | ✅ |
| 4 | Isolamento entre clientes | `shouldIsolateClientsFromEachOther` | `clienteId` de outra empresa na URL é ignorado; chamado de outra empresa → 404 em `/{id}`, `/historico` e `/sla`; cliente não recebe `tecnicoSub` | passou | ✅ |
| 5 | Técnico só vê os atribuídos a ele | `shouldShowTechnicianOnlyCurrentlyAssignedChamados` | lista e detalhe só do atribuído; trocado o técnico por SQL, 404 e lista vazia na hora | passou | ✅ |
| 6 | Técnico sem contrato nem política | `shouldNotExposeContractOrPolicyToTechnician` | JSON do detalhe e da lista sem "contrato", "politica" e `prioridadePadrao`; o gestor recebe os campos | passou | ✅ |
| 7 | Detalhe | `shouldDetailClocksAttendancesAndEvidenceSummary` (gestor, cliente e técnico) | 3 relógios CORRENDO, 1 atendimento PENDENTE com `evidencias: 0`; na lista, sem `relogios` | passou | ✅ |
| 8 | Histórico por perfil | `shouldFilterHistoryByProfile` | gestor: abertura, estado e prioridade; cliente e técnico: sem a prioridade | passou | ✅ |
| 9 | Contagem | `shouldCountByStateWithinUserScope` | os 6 estados, só do escopo (cliente: 1/1/1/0/0/0; técnico: só o atribuído) | passou | ✅ |
| 10 | `/sla` pelo acesso ao chamado | `shouldShowSlaResultToWhoeverCanSeeChamado` | sem apuração: `apurado: false` para os três; congelado: apuração para os três; outro cliente e outro técnico: 404 | passou | ✅ |
| 11 | Papéis | `AcessoPorRotaTest.shouldDenyChamadoQueriesToTokenWithoutKnownRole` | 403 nas 5 consultas para token sem papel conhecido | passou | ✅ |
| 12 | Quebra: escopo do técnico ignorado | cópia, `-Dtest=ConsultaChamadoTest` | falha | testes 5, 9 e 10 falharam | ✅ |
| 13 | Quebra: técnico recebe a visão do gestor | idem | falha | teste 6 falhou | ✅ |
| 14 | Quebra: histórico do cliente sem filtro | idem | falha | teste 8 falhou | ✅ |

## Observações

1. **Achado:** a contagem por estado faz 6 consultas (uma por estado). Fica marcada com `ponytail:` para virar `GROUP BY` se pesar.
2. **Mudança de comportamento:** `GET /api/chamados/{id}/sla` agora responde `apurado: false` também ao cliente e ao técnico com acesso, quando não há apuração. Antes eles recebiam 404, porque sem a entidade Chamado não havia como saber de quem ele era. Os 3 testes de endpoint do `ApuracaoSlaTest` foram substituídos pelo teste 10.
3. **Não verificado:** o resumo de evidências com evidências reais. O módulo evidencia não existe; sem implementação da porta `EvidenciasDoChamado`, a contagem é 0.
4. **Não verificado:** a reatribuição real. O teste troca o técnico por SQL, porque a reatribuição é de uma task futura; o critério depende só do escopo lido do banco a cada requisição.
5. **Não verificado no dev:** os endpoints com token real (o realm não permite login direto). O roteiro está na seção 7D do `docs/plano-testes-manuais.md`.
