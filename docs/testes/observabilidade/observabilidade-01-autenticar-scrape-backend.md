# Testes — [observabilidade - 01] Autenticar e autorizar o scrape de métricas do backend

> Data: 23/11/2026 · Branch: `feat/observability` · Sem commit. Testes de integração com Testcontainers; verificação de dev em cópia de `/tmp`, projeto Compose `flowsla-observabilidade-01-verificacao`, rede de mesmo nome, volumes exclusivos desse projeto. Nenhuma stack de produção foi iniciada ou reiniciada.

## Resultados

| # | O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Segurança de métricas | `PrometheusSegurancaTest`, JWTs RSA assinados, decoder real e banco temporário | Anônimo/inválido: 401; sem permissão: 403; autorizado sem usuário local: 200 | 17 cenários, 0 falhas/erros | Passou |
| 2 | Autorização e perfil de negócio | `AutorizacaoTest` e `UsuarioMeTest`, na execução focada | Regras anteriores preservadas | 12 + 1 testes, 0 falhas/erros | Passou |
| 3 | Keycloak real, primeira tentativa focada | `KeycloakAdminClientIntegrationTest` | Realm disponível e testes administrativos executados | Timeout na inicialização; `Container startup failed for image quay.io/keycloak/keycloak:26.4`; apenas a mensagem de preparação do Quarkus no log | Falhou; detalhes abaixo |
| 4 | Prova de que os testes detectam a autorização ausente | Em outra cópia de `/tmp`, trocar `hasAuthority("metrics.read")` por `authenticated()` e executar `PrometheusSegurancaTest#shouldRejectMissingMalformedOrWrongClientRole` | Teste deve falhar | 7 cenários, 7 falhas: `Status expected:<403> but was:<200>` | Falha esperada |
| 5 | Composição dos três ambientes | `docker compose --env-file .envs/.env.<ambiente> -f infra/docker-compose.yml -f infra/docker-compose.<ambiente>.yml config --quiet`, com cópias dos exemplos; overlay de isolamento em dev | Configuração válida sem imprimir segredos | Dev/test/prod: exit code 0 | Passou |
| 6 | Importação limpa de dev | Subida da cópia com Compose base + dev + overlay local | Realm com o novo client | `Realm 'flowsla' imported` e `Import finished successfully`; token real emitido | Passou após os ajustes locais de infraestrutura abaixo |
| 7 | Scrape anônimo real | HTTP no backend da cópia | 401 com Problem Details | 401 `NAO_AUTENTICADO` | Passou |
| 8 | Claims de client credentials | Token do Keycloak real, decodificado apenas em memória | Emissor configurado; audiência `app-principal`; somente a role técnica | `resource_access` contém apenas `flowsla-metrics.roles=[metrics.read]`; sem roles de realm | Passou |
| 9 | Scrape autorizado real | HTTP com o token do item 8 | 200 e gauges da outbox | 200; `outbox_eventos_pendentes` e `outbox_pendente_mais_antigo_idade_seconds` presentes | Passou |
| 10 | Nenhum usuário de negócio criado | Consulta no banco `app_principal` da cópia | Somente o gestor inicial | `usuarios_locais=1`; `/api/usuarios/me` e `/api/clientes` com conta técnica retornam 401 | Passou |
| 11 | Limite da cadeia e token adulterado | `/actuator/prometheus/outro`; JWT com assinatura alterada | 401 | Ambos 401 com Problem Details | Passou |
| 12 | Conta sem administração | Consultar `/admin/realms/flowsla/clients` com token de métricas | 403 | 403 | Passou |
| 13 | Permissão removida e restaurada | Retirar `metrics.read` da service account via Admin API na cópia, emitir novo token, consultar e restaurar em `finally` | 403 sem role; 200 após restauração | 403 / 200 | Passou |
| 14 | Audiência removida e restaurada | Remover temporariamente o mapper de audiência, emitir token e restaurar em `finally` | 401 sem audiência aceita; 200 após restauração | 401 / 200 | Passou |
| 15 | Atualização de realm existente | Criar realm temporário com usuário/client prévios, incluir client, role, mappers e atribuição via Admin API | Recursos prévios preservados; token emitido com role técnica | IDs anteriores preservados; token com `metrics.read` | Passou; procedimento equivalente pelo console não executado |
| 16 | Token real de outro emissor | Token do realm temporário do item 15 contra o backend | 401 | 401 | Passou |
| 17 | Expiração real | Reduzir temporariamente a duração do token do client para 1 s na cópia, emitir token, restaurar configuração e aguardar `exp + 65 s` | Expirado: 401; novo token: 200 | 401 / 200 | Passou |
| 18 | Scrape pela rede Docker | Container Python descartável na rede da cópia: token por `keycloak:8080`, scrape em `backend-app:8080/actuator/prometheus` | Emissor público preservado e scrape 200 | Emissor igual ao configurado no backend; 200 com gauges da outbox | Passou |
| 19 | Migrações no ambiente dev | Consulta somente leitura a `flyway_schema_history` de `app_principal` | Migrações existentes aplicadas, sem migração nova | Última versão 15; 15 migrações com sucesso | Passou |
| 20 | Primeira suíte completa em UTC | `TZ=UTC ./mvnw clean test` | Nenhuma falha | 556 testes, 2 falhas, 0 erros, 0 ignorados: seleção de cadeia no teste de cabeçalhos e expectativa de PDF | Falhou; detalhes abaixo |
| 21 | Sintaxe e referências da documentação | Parse do JSON do realm; `bash -n` no bloco da seção 3A; conferir destinos dos links locais | Arquivos e comandos válidos | Sem erros | Passou |
| 22 | Encerramento do ambiente descartável | `docker compose -p flowsla-observabilidade-01-verificacao ... down --volumes` com os mesmos arquivos da subida | Remover somente recursos criados para a verificação | Containers, rede e três volumes desse projeto removidos | Passou |
| 23 | Origem da falha de PDF | Exportar `git archive HEAD` (`44a02a9`) para outra cópia e executar `TZ=UTC ./mvnw -Dtest=RelatorioSlaTest#shouldExportPdfWithFiltersGenerationAndAllReportSections test` | Verificar se a falha existe sem a tarefa de observabilidade | 1 teste, 1 falha, 0 erros: faltam `Nome não localizado` e o UUID do técnico excluído pelo filtro, como na suíte completa | Falha anterior confirmada |
| 24 | Segunda suíte completa em UTC, após ajuste do teste de cabeçalhos e das fixtures | `TZ=UTC ./mvnw clean test`, encerrada a pedido do usuário | Registrar somente casos concluídos antes do desligamento | 101 casos concluídos, 0 falhas e 0 erros antes do primeiro shutdown hook; os 17 de métricas passaram. Execução total não concluída | Interrompida a pedido do usuário |

## Cenários automatizados novos

Os 17 casos de `PrometheusSegurancaTest` abrangem: anônimo; conta autorizada sem usuário local; sete variações de claim ausente, malformado, role errada ou role de outro client; papel de gestor/role de realm sem autoridade técnica; expiração; emissor; audiência; outra chave de assinatura; e três caminhos fora da cadeia exclusiva. Também conferem Problem Details, ausência de cookie de sessão e presença dos gauges no scrape autorizado.

O decoder é o de produção com `issuer-uri`, `jwk-set-uri` e audiência configurados para a fixture de testes. Não foi usado `jwt()` para pular a validação criptográfica.

## Observações e achados

1. A primeira execução focada terminou com `Tests run: 31, Failures: 0, Errors: 1, Skipped: 0`: os 30 casos HTTP passaram, mas o container Keycloak excedeu o limite de inicialização de três minutos. A saída foi `Timed out waiting for URL to be accessible (.../realms/flowsla/.well-known/openid-configuration should return HTTP [200])`, com `Connection reset`. Esse resultado não foi tratado como teste aprovado.
2. Na primeira subida de dev isolado, o PostgreSQL foi marcado como unhealthy durante o initdb e depois ficou saudável. A saída do Compose foi `dependency failed to start: container flowsla-observabilidade-01-verificacao-postgres-1 is unhealthy`. A verificação continuou após confirmar a saúde do mesmo banco, preservando seus volumes.
3. O RabbitMQ da cópia falhou com `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`. No volume temporário novo, o cookie pertencia a `root:root`, modo 400, enquanto o broker usa UID 100/GID 101. Corrigido apenas o dono desse arquivo no volume `flowsla-observabilidade-01-verificacao_rabbitmq-dev` para `rabbitmq:rabbitmq`; o broker iniciou e ficou saudável. Nenhum volume `infra_*` foi alterado e essa correção não foi incorporada ao código da task.
4. `chcon -R -t container_file_t infra/rabbitmq`, na cópia, retornou `can't apply partial context to unlabeled file`. Os mounts `:z` foram mantidos; a stack pôde ser verificada neste host sem mudar sua configuração SELinux.
5. A mutação proposital ficou somente na cópia descartável. A implementação do repositório manteve `hasAuthority("metrics.read")`; os 17 testes passaram também na execução da suíte com `TZ=UTC`.
6. Tokens e segredos reais da verificação ficaram na cópia ignorada/na memória dos scripts, fora do versionamento; as evidências registram status e nomes de métricas.
7. Na primeira suíte completa, `ConsultaEvidenciaTest.shouldWriteSecurityHeadersEagerlySoTheStreamingThreadDoesNotRace` falhou com `expected: true but was: false`. O teste pegava o primeiro `HeaderWriterFilter` de todas as cadeias e passou a selecionar o da nova cadeia de métricas. Ajustado para inspecionar o bean `api`, onde ocorrem os downloads assíncronos que ele protege; a configuração de produção dos cabeçalhos da API permaneceu a mesma.
8. A outra falha da primeira suíte foi `RelatorioSlaTest.shouldExportPdfWithFiltersGenerationAndAllReportSections`: a requisição filtra `tecnicoSub` pelo técnico cadastrado, mas a asserção exige também `Nome não localizado` e o UUID de outro técnico, excluído pelo filtro. A saída foi `but could not find: ["Nome não localizado", "<UUID do técnico sem cadastro>"]`. A mesma falha foi reproduzida numa cópia do commit original `44a02a9`, sem as alterações desta tarefa. O módulo de relatórios não foi alterado nesta tarefa.

9. A segunda suíte foi interrompida por solicitação do usuário, com SIGTERM apenas na JVM Surefire desta execução. Durante o encerramento, os contextos Spring foram fechados e o runner ainda tentou outros testes, gerando `SpringApplicationShutdownHook.assertNotInProgress` e erros de contexto. O resumo posterior ao sinal (`202 testes, 1 falha, 71 erros`) não é uma execução válida da suíte e não foi usado para validar a implementação. A tabela registra os casos concluídos antes do desligamento.

## Não verificado nesta tarefa

- Conclusão da segunda suíte completa: o usuário optou por executá-la depois. O ajuste de seleção da cadeia em `ConsultaEvidenciaTest` foi compilado, mas esse teste ainda não havia sido alcançado na segunda execução. Para repetir: `TZ=UTC ./mvnw clean test`; a falha anterior do PDF continua registrada separadamente.

- Operação em produção e inicialização da stack de test por Compose; para esses ambientes foi feita somente a validação estática de configuração.
- Aplicação das instruções pela interface gráfica do Keycloak: a atualização de realm existente foi verificada pela Admin API em realm descartável.
- Instalação do Prometheus/Grafana, secret de Compose para o coletor, scrape periódico e renovação automática de tokens; pertencem às tarefas seguintes.
- Mudança de estado dos gauges entre coletas, dashboard, métricas do MS e alertas operacionais; pertencem às tarefas seguintes.
