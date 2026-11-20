# Testes — [retencao - 04] Remover objetos órfãos do storage

> 18/11/2026 · Branch `feat/retencao` · Java 21, PostgreSQL e MinIO descartáveis via Testcontainers

| O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Idade, vínculo e paginação | `LimpezaOrfaosTest`, uma chave por página, usando horários lidos do MinIO | Preservar órfão de 23 horas e no limite de 24, remover órfãos vencidos de várias páginas e preservar chaves registradas e evidências em processamento | O órfão permaneceu nos dois limites. Três órfãos foram removidos e as sete chaves protegidas permaneceram após a reexecução. | Passou |
| Quebra do corte | Incluir o instante exato de 24 horas em uma cópia temporária | O teste detecta a exclusão antecipada | Falhou na asserção que exigia a chave na listagem aos 24 horas | Quebra detectada |
| Suíte completa das duas tasks | `TZ=UTC` com `mvnw.cmd -B -ntp clean test` e JVM iniciada em UTC | Todos os testes passam | 494 testes, duas falhas e zero erros. Falharam as comparações textuais de `ContratoNotificacaoTest` e `OpenApiContratoTest`; as duas tasks de retenção passaram. | Falhou |
| Repetição dos contratos | Rodar as duas classes com os arquivos regravados na cópia temporária | Passar com o formato gerado no Windows | 18 testes passaram. Os nove JSONs coincidiram com os do repositório após normalizar os fins de linha. | Passou |

## Observações

- O relógio foi avançado via instante informado ao passo. Os objetos usaram a data de modificação que o MinIO registrou.
- O roteiro manual do ambiente integrado não foi executado.
