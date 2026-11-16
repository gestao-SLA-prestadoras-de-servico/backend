# Testes — [chamado - 08] Ativar a preferência de notificação do gestor

> Executados em 16/11/2026, na branch `feat/chamados_6-10` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 410 testes, 0 falhas (`PreferenciaNotificacaoTest` 5, `CancelamentoChamadoTest` 9, `InicioAtendimentoTest` 11, `AcessoPorRotaTest` +2 e os 383 anteriores). Na rodada anterior, o `OpenApiContratoTest` falhou e regenerou o `openapi.json`, como previsto. | ✅ |
| 2 | Ativar e desativar | `PreferenciaNotificacaoTest.shouldActivateAndDeactivateOwnPreference` | 204; `notificarConclusao` true e depois false no detalhe; 2 auditorias `{ativa: true}` e `{ativa: false}` | passou | ✅ |
| 3 | Idempotência | `shouldBeIdempotent` | ativar duas vezes: 1 linha e 1 auditoria; desativar sem ter ativado: 204 sem auditoria | passou | ✅ |
| 4 | Independência entre gestores | `shouldKeepPreferencesIndependentPerManager` | A ativa e B segue false; depois B ativa e A desativa: só B em `gestoresComPreferencia` | passou | ✅ |
| 5 | 404 e papéis | `shouldAnswer404ForUnknownChamadoAndDenyOtherProfiles` e `AcessoPorRotaTest` | chamado inexistente → 404; cliente e técnico → 403 no PUT e no DELETE; nenhuma auditoria | passou | ✅ |
| 6 | Campo só para o gestor | `shouldNotExposePreferenceToClient` | o detalhe do cliente não tem `notificarConclusao` | passou | ✅ |
| 7 | Quebra: ativar sem conferir se já existe | cópia, `-Dtest=PreferenciaNotificacaoTest` | falha | teste 3: 204 esperado, veio 500 (a UNIQUE barrou a duplicata) | ✅ |
| 8 | Quebra: desativar apaga a preferência de todos os gestores | idem | falha | testes 3 e 4 falharam | ✅ |
| 9 | Dev: migração V14 | stack de dev a partir da cópia (`-p flowsla-verificacao`) | V14 aplicada com a UNIQUE `(chamado_id, gestor_sub)` | V14 com sucesso; `preferencia_notificacao_uk` presente (item 14 da [chamado - 10]) | ✅ |

## Observações

1. **Não verificado:** o uso de `gestoresComPreferencia` no aviso de conclusão, que é de uma task futura.
2. **Não verificado no dev:** os endpoints com token real (o realm não permite login direto). O roteiro está na seção 7F do `docs/plano-testes-manuais.md`.
