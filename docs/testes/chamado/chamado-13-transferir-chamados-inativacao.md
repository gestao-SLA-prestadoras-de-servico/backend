# Testes — [chamado - 13] Transferir chamados na inativação de técnico

> Executados em 16/11/2026, na branch `feat/chamados11-15` (alterações ainda não commitadas), com Testcontainers. As quebras propositais foram feitas numa cópia do projeto, fora do repositório. A verificação no dev usou o ambiente de `make ci-run-dev`, com o app reiniciado.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 510 testes, 0 falhas (ver chamado-11) | ✅ |
| 2 | Contagem por estado | `TransferenciaTecnicoTest.shouldCountTicketsToTransferByState` (ATRIBUIDO, 2 EM_ATENDIMENTO, ENCERRADO e CANCELADO) | `{ATRIBUIDO: 1, EM_ATENDIMENTO: 2, RESOLVIDO: 0}`, sem chave para os estados finais; 404 para id inexistente; 403 para técnico | passou | ✅ |
| 3 | Inativação pela API | `shouldTransferAllTicketsWhenTechnicianIsInactivatedWithoutChangingClocks` (um chamado em cada estado, com relógios; Keycloak simulado) | 204; os três com o substituto; estados ATRIBUIDO, ATRIBUIDO e RESOLVIDO; linhas de `relogio_sla` idênticas; 3 ATRIBUICAO ao substituto; nenhuma REMOCAO; contagem zerada | passou | ✅ |
| 4 | Regras por estado (chamado-07) | `shouldTransferEachStateAccordingToRn134` | sem regressão | passou | ✅ |
| 5 | Rota só do gestor | `AcessoPorRotaTest.shouldDenyManagerRoutesBeforeValidatingBody` | técnico e cliente recebem 403 | passou | ✅ |
| 6 | Quebra: contagem sem separar por estado | cópia, as três classes de teste novas | falha | item 2 falhou | ✅ |
| 7 | Dev: 7K.12 | `roteiro7k.sh` (fora do repositório), comparando com uma contagem SQL de `chamado` | as mesmas contagens | `{"ATRIBUIDO":0,"EM_ATENDIMENTO":2,"RESOLVIDO":0}`, igual ao SQL | ✅ |

## Observações

1. **A transferência já existia** (chamado-07). Esta task acrescentou a pré-visualização e o teste ponta a ponta pela API.
2. **Não verificado nesta task:** o rollback da transferência quando a gravação local da inativação falha. Ele vale porque a transferência roda na transação da inativação (`MANDATORY`). O teste de compensação da autenticação-06 cobre a falha local, mas com a porta simulada.
3. **Não verificado no dev:** a inativação de um técnico com chamados. A massa de dev seria alterada de forma irreversível (técnico inativo no Keycloak), então não foi feita.
