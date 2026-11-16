# Testes — [chamado - 06] Registrar o primeiro posicionamento

> Executados em 16/11/2026, na branch `feat/chamados_6-10` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 383 testes, 0 falhas (`PosicionamentoChamadoTest` 11, `AtribuicaoChamadoTest` 10, `TransferenciaTecnicoTest` 2 e os 360 anteriores). Na rodada anterior, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Posicionamento completo | `PosicionamentoChamadoTest.shouldRegisterFirstPositionFulfillingResponseAndWritingEmail` | 200 com texto e `posicionadoEm`; RESPOSTA CUMPRIDO; `POSICIONAMENTO` no canal `email` para o e-mail de contato, com o texto e o número; histórico `POSICIONAMENTO`; auditoria sem o texto (`{}`) | passou | ✅ |
| 3 | Só uma vez | `shouldRejectSecondPosition` | 422 `POSICIONAMENTO_JA_REGISTRADO`; texto original mantido; 1 e-mail | passou | ✅ |
| 4 | Posicionamento atrasado | `shouldViolateResponseWhenPositionIsLate` | RESPOSTA VIOLADO com `violadoEm` = vencimento; `VIOLACAO` na outbox com o `eventoId` determinístico | passou | ✅ |
| 5 | Rollback sem e-mail | `shouldNotWriteEmailWhenTransactionRollsBack` (trigger temporário falha no histórico, depois do e-mail) | 500; nenhum e-mail, texto nulo, RESPOSTA sem cumprimento | passou | ✅ |
| 6 | Estados não finais | `shouldAcceptPositionInAnyNonFinalState` (ATRIBUIDO, EM_ATENDIMENTO, RESOLVIDO) | 200 | passou | ✅ |
| 7 | Estados finais | `shouldRejectPositionInFinalState` (ENCERRADO, CANCELADO) | 422 `TRANSICAO_INVALIDA`; nenhum e-mail | passou | ✅ |
| 8 | Validação | `shouldValidateText` | texto em branco e com 4001 caracteres → 400; chamado inexistente → 404 | passou | ✅ |
| 9 | Papéis | `shouldAllowOnlyManager` e `AcessoPorRotaTest` | 403 para cliente e técnico, antes de validar o corpo | passou | ✅ |
| 10 | Quebra: posicionar duas vezes | cópia, `-Dtest=PosicionamentoChamadoTest` | falha | teste 3: 422 esperado, veio 200 | ✅ |
| 11 | Quebra: sem `cumprir(RESPOSTA)` | idem | falha | testes 2 e 4 falharam (relógio CORRENDO) | ✅ |
| 12 | Quebra: e-mail gravado numa transação própria (`REQUIRES_NEW`) | idem | falha | teste 5 falhou (o e-mail ficou depois do rollback) | ✅ |

## Observações

1. **Não verificado (depende de terceiro):** a chegada do e-mail ao Mailpit. O repositório `ms-notifications` ainda só tem o commit inicial (README), então não há consumidor. O backend grava o evento correto na outbox (teste 2), e a publicação no RabbitMQ é a da outbox-03.
2. **Achado:** a falha simulada usa um trigger temporário no banco em vez de spy, porque o spy de um bean transacional esbarra no proxy (mesmo achado da chamado-03).
3. **Não verificado no dev:** o endpoint com token real (o realm não permite login direto). O roteiro está na seção 7E do `docs/plano-testes-manuais.md`.
