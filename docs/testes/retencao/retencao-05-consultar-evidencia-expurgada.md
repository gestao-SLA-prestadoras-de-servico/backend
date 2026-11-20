# Testes — [retencao - 05] Consultar evidência expurgada

> 18/11/2026 · Branch `feat/retencao` · Java 21 e Testcontainers com dados sintéticos

| O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Consulta por perfil | `ConsultaEvidenciaTest.shouldReturnPurgedEvidenceMetadataOnlyForManager` | 200 com metadados para gestor, 403 para técnico e cliente, 401 sem token e 404 para ID inexistente | Todas as respostas corresponderam ao esperado | Passou |
| Listagem do chamado | `shouldListEvidenceGroupedByAttendanceOrderedBySentDateForAllRoles` | Gestor vê `EXPURGADA` sem miniatura. Os outros perfis recebem apenas os demais itens. | Os três perfis tiveram as listagens esperadas | Passou |
| Regressões de consulta e armazenamento | `ConsultaEvidenciaTest` | Download continua em 410 para conteúdo expurgado, preserva isolamento e não expõe operação de exclusão | 16 testes, 0 falhas e 0 erros | Passou |
| Regressão no filtro de perfil | Remover o filtro da listagem em cópia temporária | O teste detecta o item expurgado na listagem de um perfil diferente de gestor | Falhou com `$[1].evidencias.length()` igual a 2, quando esperava 1 | Quebra detectada |
| Suíte completa, incluindo o contrato OpenAPI | `TZ=UTC` e JVM iniciada em UTC com `mvnw.cmd -B -ntp clean test` na cópia com os JSONs regenerados | Todos os testes passam | 495 testes, 0 falhas, 0 erros, 0 ignorados | Passou |

## Observações

- Na primeira execução, `OpenApiContratoTest` regravou o documento para incluir a nova rota. `ContratoNotificacaoTest` acusou diferença textual dos JSONs no Windows. Os schemas de notificação coincidiram após normalização dos fins de linha e ficaram fora do diff.
- Na implantação local posterior, a migração aplicada chegou à V15 e o backend
  e o microsserviço responderam `UP`. O OpenAPI em execução publicou a nova
  rota, a chamada sem token retornou 401 e ambos os serviços aceitaram CORS da
  origem do frontend.
- A build do Angular passou e o servidor em `localhost:4200` entregou o bundle
  da tela com HTTP 200. O Keycloak aceitou o retorno `/app` e apresentou o
  formulário. A sessão autenticada no navegador ficou sem verificação, pois a
  senha local de gestor não estava disponível na configuração.
- O agendamento foi desativado no backend local durante a validação para evitar
  expurgo dos dados existentes.
