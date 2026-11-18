# Testes — [retencao - 02] Expurgar evidências vencidas

> 18/11/2026 · Branch `feat/retencao` · Java 21, JVM iniciada em UTC e serviços descartáveis via Testcontainers

| O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Elegibilidade, paginação, MinIO e auditoria | `ExpurgoEvidenciasTest.shouldPurgeOnlyOldFinalTicketsAndKeepMetadataAndAudit` | Remover todos os objetos dos chamados finais vencidos e manter o chamado resolvido e o que está no limite | Os dois prefixos elegíveis ficaram vazios, com metadados e auditoria preservados. Os outros prefixos permaneceram. | Passou |
| Reexecução após falha | Impedir a auditoria por restrição SQL descartável em `shouldRetryAfterStorageDeletionAndDatabaseRollback` | Repetir com prefixo vazio e concluir a alteração do estado e a auditoria | A primeira tentativa esvaziou o prefixo e reverteu o banco. A segunda gravou `EXPURGADA` e uma auditoria. | Passou |
| Processamento e módulos | `ProcessadorEvidenciasTest` e `ModularidadeTest` | Manter o processamento e as fronteiras dos módulos | 13 testes passaram | Passou |
| Quebra do corte | Incluir o instante exato de 180 dias em cópia temporária | O teste detecta a exclusão antecipada | Falhou com `expected: 2 but was: 3` | Quebra detectada |

## Observações

- A execução focada teve 15 testes, 0 falhas e 0 erros.
- O roteiro manual do ambiente integrado não foi executado.
