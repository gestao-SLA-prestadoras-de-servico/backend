# Testes — [chamado - 07] Atribuir e reatribuir o técnico

> Executados em 16/11/2026, na branch `feat/chamados_6-10` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 383 testes, 0 falhas (ver [chamado - 06]); `InativacaoUsuarioTest` e `ConsultaUsuarioTest` seguem passando com a porta simulada | ✅ |
| 2 | ABERTO → ATRIBUIDO | `AtribuicaoChamadoTest.shouldAssignOpenChamado` | ATRIBUIDO com o técnico; relógios idênticos; `ATRIBUICAO` ao técnico e nenhuma `REMOCAO`; auditoria `MUDANCA_ESTADO` e `ATRIBUICAO`; nenhum atendimento criado | passou | ✅ |
| 3 | Reatribuição em ATRIBUIDO | `shouldReassignKeepingStateAndNotifyBothTechnicians` | continua ATRIBUIDO com o técnico novo; relógios idênticos; `REMOCAO` ao anterior; auditoria `REATRIBUICAO {anterior, novo}` | passou | ✅ |
| 4 | Reatribuição em EM_ATENDIMENTO | `shouldReturnToAssignedAndInterruptAttendance` | volta a ATRIBUIDO; atendimento INTERROMPIDO com `encerrado_em`, a linha fica; relógios idênticos | passou | ✅ |
| 5 | RESOLVIDO e estados finais | `shouldRejectAssignmentFromResolvedOn` | 422 `TRANSICAO_INVALIDA`; nenhum aviso | passou | ✅ |
| 6 | Técnico inválido | `shouldRejectInvalidTechnician` (inativo, gestor, sub inexistente, o mesmo técnico) | 422 `REATRIBUICAO_NAO_PERMITIDA` | passou | ✅ |
| 7 | Versão desatualizada | `shouldRejectStaleVersion` | 409 `CONFLITO_VERSAO`; técnico anterior mantido | passou | ✅ |
| 8 | Atribuições concorrentes | `shouldRejectSecondOfConcurrentAssignments` (2 threads, mesma versão) | uma 200 e uma 409; 1 aviso `ATRIBUICAO` | passou | ✅ |
| 9 | Papéis e validação | `shouldAllowOnlyManager` e `AcessoPorRotaTest` | 403 para cliente e técnico; 400 sem `version` | passou | ✅ |
| 10 | Carga em `GET /tecnicos` | `TransferenciaTecnicoTest.shouldShowRealLoadInTechnicianListing` | 3 (ATRIBUIDO, EM_ATENDIMENTO, RESOLVIDO; ENCERRADO e CANCELADO fora); substituto com 0 | passou | ✅ |
| 11 | Transferência por inativação | `shouldTransferEachStateAccordingToRn134` | ATRIBUIDO e EM_ATENDIMENTO viram ATRIBUIDO com o substituto (atendimento interrompido); RESOLVIDO mantém o estado; ENCERRADO não muda; auditoria por chamado; `ATRIBUICAO` ao substituto; nenhuma `REMOCAO` ao inativado | passou | ✅ |
| 12 | Quebra: reatribuição sem interromper | cópia, `-Dtest=AtribuicaoChamadoTest,TransferenciaTecnicoTest` | falha | testes 4 e 11 falharam | ✅ |
| 13 | Quebra: sem `REMOCAO` | cópia, `-Dtest=AtribuicaoChamadoTest` | falha | teste 3 falhou | ✅ |
| 14 | Quebra: sem conferir a versão | idem | falha | teste 7: 409 esperado, veio 200 | ✅ |
| 15 | Quebra: atribuição reiniciando os relógios | idem | falha | testes 2, 3 e 4 falharam | ✅ |
| 16 | Quebra: troca de técnico avisando remoção também na transferência | cópia, `-Dtest=TransferenciaTecnicoTest` | falha | teste 11 falhou | ✅ |

## Observações

1. **Achado:** com a porta `ChamadosDoTecnico` implementada no próprio `AtribuicaoService`, o `@MockitoBean` dos testes do identidade substituía o serviço inteiro e o contexto não subia (`BeanNotOfRequiredTypeException`). A porta foi para uma classe própria, `TransferenciaChamados`.
2. **Mudança de comportamento:** a inativação de técnico com chamados não finalizados passa a exigir substituto e a transferir os chamados. A [chamado - 13] fica adiantada nesse ponto.
3. **Não verificável ainda:** a preservação das fotos. O módulo evidencia não existe; o teste prova que o atendimento interrompido continua no banco.
4. **Não verificado:** a inativação real ponta a ponta (com Keycloak) usando a porta de verdade. O `InativacaoUsuarioTest` usa a porta simulada, e a transferência foi testada chamando a porta diretamente numa transação.
5. **Não verificado no dev:** o endpoint com token real (o realm não permite login direto). O roteiro está na seção 7E do `docs/plano-testes-manuais.md`.
