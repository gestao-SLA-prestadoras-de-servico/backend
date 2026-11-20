# Testes — [retencao - 03] Expurgar auditoria com mais de cinco anos

> 18/11/2026 · Branch `feat/retencao` · Java 21 e PostgreSQL descartável via Testcontainers

| O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Suíte completa | `TZ=UTC` com `mvnw.cmd -B -ntp clean test`, Java 21 e JVM iniciada em UTC | Todos os testes passam | 413 testes, 2 falhas, 0 erros e 0 ignorados. Falharam as comparações textuais em `ContratoNotificacaoTest` e `OpenApiContratoTest`. | Falhou |
| Repetição dos contratos | Rodar as duas classes com o JSON regravado pelos testes na cópia temporária | As comparações passam no mesmo ambiente | 18 testes, 0 falhas, 0 erros e 0 ignorados | Passou |
| Lotes, fronteira, isolamento e métricas | `shouldPurgeExpiredAuditRecordsInBatchesEvenWhenOutboxFails` | A auditoria segue após a falha da outbox, remove 101 vencidos em mais de um lote e preserva dois registros no limite ou mais novos | Restaram as duas datas esperadas. A métrica contou 101 remoções e uma falha na outbox. | Passou |
| Proteção por trigger | `shouldRejectDirectDeletionOfAuditRecordStillUnderRetention` | PostgreSQL rejeita a exclusão direta de registro a um dia de completar cinco anos | Exclusão rejeitada com RN-124 | Passou |
| Quebra no corte | Trocar `<` por `<=` em uma cópia e rodar `ExpurgoAuditoriaTest` | O teste detecta exclusão de registro no instante exato de cinco anos | Falhou porque `2020-01-02T12:00:00Z` desapareceu do resultado esperado | Quebra detectada |

## Observações

- Os dois testes da auditoria e o da outbox passaram na execução focada.
- Os nove JSONs regravados na cópia ficaram idênticos aos do checkout após normalizar os fins de linha. Na execução anterior, com apenas retenção-01, foram 392 testes, duas falhas de comparação textual e dois erros de inicialização do Keycloak. Os cenários de Keycloak passaram na suíte de retenção-03.
- O roteiro manual no ambiente integrado não foi executado. Os dados usados eram sintéticos, em containers descartáveis.
