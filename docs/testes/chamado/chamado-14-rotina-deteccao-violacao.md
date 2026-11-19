# Testes — [chamado - 14] Rotina de detecção de violação (a cada 15 minutos)

> Executados em 17/11/2026, na branch `feat/chamados11-15` (alterações ainda não commitadas), com Testcontainers. As quebras propositais foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 523 testes, 0 falhas; `openapi.json` sem mudança | ✅ |
| 2 | Os três relógios com o tempo avançando | `RotinaViolacaoTest.shouldMarkEachClockWithItsDueTimeAsTimeAdvances` | no vencimento exato, nada; 1 min depois, só aquele relógio, na ordem RESPOSTA, CAMPO e RESOLUCAO; `violado_em = vence_em` nos três; `VIOLACAO_SLA` sem ator no histórico; aviso com o `eventoId` determinístico para o técnico e o gestor; `sla.violacoes` +1 por relógio | passou | ✅ |
| 3 | Duas passadas seguidas | `shouldNotNotifyTwiceWhenRunTwice` | 3 avisos e 3 itens de histórico, não 6 | passou | ✅ |
| 4 | Estados do chamado | `shouldIncludeResolvedAndSkipClosedAndCancelled` | RESOLVIDO com os 3 violados; ENCERRADO e CANCELADO sem violação e sem aviso | passou | ✅ |
| 5 | Sem técnico | `shouldNotifyOnlyManagersWithoutTechnician` | destinatários só com perfil GESTOR, incluindo o gestor do teste | passou | ✅ |
| 6 | Relógio em uso por outra transação | `shouldSkipLockedClockAndMarkItInNextRun` (outra thread trava o RESPOSTA com `for update`) | a rotina não espera (limite de 30 s); marca CAMPO e RESOLUCAO e pula RESPOSTA; liberada a trava, a passada seguinte marca RESPOSTA | passou | ✅ |
| 7 | Lotes | `shouldProcessAllClocksInBatchesSkippingFinalizedOnes` (lote 2, um CANCELADO e três abertos) | os 9 relógios dos abertos violados, o cancelado intacto, sem laço infinito | passou | ✅ |
| 8 | Quebra: vencido no instante exato (`<=`) | cópia, `RotinaViolacaoTest` e `ValidacaoAutomaticaTest` | falha | item 2 falhou | ✅ |
| 9 | Quebra: sem pular os finalizados | idem | falha | itens 4 e 7 falharam | ✅ |
| 10 | Quebra: sem `SKIP LOCKED` | idem | falha | item 6 falhou (a rotina ficou esperando a trava) | ✅ |
| 11 | Quebra: cursor que não avança | idem | falha | item 7 falhou (estourou o tempo) | ✅ |
| 12 | Quebra: sem o histórico | idem | falha | itens 2 e 3 falharam | ✅ |

## Observações

1. **Antes de cada teste,** a rotina roda uma vez com o relógio em 2100. Ela varre o banco inteiro, e essa passada marca o que outros testes deixaram vencido. Assim, as contagens e a métrica ficam só do teste.
2. **Não verificado no dev:** o disparo pelo cron real e a métrica no `/actuator/prometheus`. As portas fixas do compose impedem uma segunda pilha ao lado do dev no ar, e reiniciar o app do dev mexeria nos dados dele e recriaria arquivos de root no `target/`. O roteiro está na seção 7L do plano manual.
3. **Não verificado:** a corrida em que o usuário lê o relógio antes da rotina e grava depois. Pelo `@Version` do relógio, ele recebe 409, mas não há teste desse caso.
4. **Limite conhecido:** os relógios vencidos de chamados ENCERRADO e CANCELADO são relidos e pulados a cada passada (há um comentário `ponytail` na `RotinaViolacao`). Se esse volume crescer, o caminho é inativar os relógios no fim do chamado.
