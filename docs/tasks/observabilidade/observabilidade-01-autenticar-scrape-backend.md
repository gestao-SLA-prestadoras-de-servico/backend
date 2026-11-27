# [observabilidade - 01] Autenticar e autorizar o scrape de métricas do backend

> Requisitos: RF-63 e RF-64 · Depende de: [autenticação - 01], [autenticação - 02] e [outbox - 03]

## Contexto

O backend já expõe métricas Micrometer na porta da API, 8080. A cadeia de segurança anterior exigia um usuário ativo em `usuario` para qualquer token, inclusive no Actuator, e não distinguia a permissão de scrape. Uma conta de serviço precisa coletar as métricas sem se tornar um usuário de negócio nem receber permissões administrativas.

## Problemática: critérios de aceite × solução

| Critério de aceite | Solução |
| --- | --- |
| Client OAuth2 exclusivo, sem administração | `flowsla-metrics`, confidencial, somente service account, role própria `metrics.read`, sem roles de negócio ou `realm-management`. |
| Scrape em `backend-app:8080/actuator/prometheus` | Mesma porta da API; nenhuma porta de gerenciamento adicional. |
| Sem token ou com JWT inválido/expirado: 401 | A cadeia exclusiva reutiliza o decoder configurado com assinatura, validade, emissor e audiência `app-principal`, e os handlers de Problem Details. |
| JWT válido sem a permissão: 403 | Somente `resource_access.flowsla-metrics.roles` pode conceder a authority `metrics.read`; roles de realm ou de outro client não concedem acesso. |
| JWT autorizado: 200 | `JwtAuthenticationConverter` próprio na cadeia prioritária, sem `UsuarioCorrenteConverter` nem consulta ao cadastro de usuários. |
| Token de métricas não acessa a API | A cadeia exclusiva casa somente `/actuator/prometheus`; o restante continua exigindo usuário local e autorização de negócio. |
| Realm existente atualizado sem apagar dados | Procedimento pelo console abaixo; o JSON de importação atende instalações novas. |

## Passos técnicos realizados

1. Adicionada uma `SecurityFilterChain` com `@Order(1)` para o caminho de métricas, stateless e sem cache de requisições. O endpoint é do Actuator; não foi criado controller intermediário para `@PreAuthorize`.
2. Adicionado o client, a role de client e a atribuição à conta de serviço no realm versionado. `fullScopeAllowed=false`, sem client scopes padrão/opcionais; dois mappers dedicados publicam a audiência `app-principal` e somente as roles de `flowsla-metrics`.
3. Acrescentada `KEYCLOAK_METRICS_CLIENT_SECRET` aos três exemplos de ambiente e ao repasse do Compose para o Keycloak. A variável é obrigatória ao resolver o Compose para evitar um client novo com segredo vazio. O valor real fica em `.envs/`, ignorado pelo Git.
4. Adicionados testes HTTP com JWTs assinados pelo `TokenTeste`, passando pelo decoder real, cobrindo permissões, assinatura, emissor, audiência, expiração e isolamento das rotas. A fixture do Keycloak real também recebe o novo placeholder.
5. Atualizados README e roteiro manual, inclusive as consultas de métricas de evidências e SLA, que antes usavam token de gestor.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Autenticação técnica | Cadeia exclusiva, aprovada pelo usuário | Uma conta de serviço não depende da existência/atividade de usuário local; a API continua usando o conversor de negócio. |
| Permissão | Role de client `metrics.read`, aprovada pelo usuário | Separação dos papéis CLIENTE/TECNICO/GESTOR; não altera `Papel`. |
| Client | `flowsla-metrics`, conforme plano aprovado | Identifica a credencial de coleta sem reutilizar a conta administrativa. |
| Roles expostas | Mapper dedicado ao próprio client, sem scopes padrão/opcionais | Limita os claims às permissões necessárias à coleta. |
| Transporte | Porta existente 8080 | O coletor usa o DNS interno `backend-app`, sem nova porta publicada. |
| Etapas seguintes | Secret do Prometheus na tarefa 2; scrape e renovação automática na tarefa 3 | Esta entrega prepara o emissor e o endpoint; não instala a stack de monitoramento. |

## Atenção ao atualizar um ambiente existente

O import no boot **ignora realms existentes**. Não apague realm, usuários, banco ou volumes e não use `make run-dev-down` para aplicar esta mudança. Acrescente a variável ao arquivo `.envs/.env.<ambiente>` antes de usar o Compose atualizado. Mudar essa variável ou reiniciar o container não altera o segredo de um client já criado.

No console administrativo do Keycloak, selecione o realm **flowsla** e execute:

1. **Clients → Create client**: OpenID Connect, Client ID `flowsla-metrics`. Habilite **Client authentication** e **Service accounts roles**; desabilite **Standard flow**, **Direct access grants**, **Implicit flow** e demais grants opcionais. Não configure redirect URIs ou web origins.
2. Em **Client scopes**, remova os scopes padrão/opcionais atribuídos ao client. No scope dedicado `flowsla-metrics-dedicated`, desligue **Full scope allowed**. A role do próprio client continua elegível para seu token. Preserve os scopes de todos os outros clients.
3. Em **Roles**, crie `metrics.read`, sem composite roles. Em **Service accounts roles**, atribua apenas essa role de `flowsla-metrics`. Não atribua papéis de negócio nem de `realm-management`.
4. No scope dedicado, **Mappers → Configure a new mapper → Audience**: nome `audience-app-principal`, **Included Custom Audience** `app-principal`, **Add to access token** ligado e **Add to ID token** desligado.
5. Crie também um mapper **User Client Role**: nome `metrics-read`, **Client ID** `flowsla-metrics`, sem prefixo, **Token Claim Name** `resource_access.flowsla-metrics.roles`, **Claim JSON Type** `String`, **Multivalued** ligado, **Add to access token** ligado e **Add to ID token/UserInfo** desligados. Esses valores correspondem ao `protocolMappers` do JSON versionado.
6. Em **Credentials**, use uma credencial própria e registre o valor efetivo em `KEYCLOAK_METRICS_CLIENT_SECRET` no `.envs` do ambiente. Para um client já existente, confira as configurações antes de alterá-las e preserve sua credencial quando possível. Na rotação, atualize o segredo no Keycloak e nos consumidores; o arquivo de ambiente sozinho não faz a rotação.
7. Obtenha um token por `client_credentials` e confira localmente `iss`, `aud` e `resource_access`. Em dev, o token solicitado por `http://keycloak:8080/realms/flowsla/protocol/openid-connect/token`, de dentro da rede Docker, deve continuar com `iss=http://localhost:8081/realms/flowsla` (ou o hostname público da cópia isolada). Em prod, alinhe o hostname público do Keycloak ao `KEYCLOAK_ISSUER_URI`; não desative a validação do emissor para contornar diferença de endereço.

O client pode ser incluído no realm em execução pelo console, sem reiniciar o Keycloak. O backend precisa receber o código atualizado para aplicar a autorização. Tokens de gestor deixam de autorizar a leitura das métricas.

Não há migração SQL, endpoint novo ou código de erro novo. O contrato OpenAPI de negócio permanece igual. A instalação do Prometheus, seu secret de Compose e a renovação automática de tokens não fazem parte desta tarefa.

## Como verificar

- Manual: [seção 3A do roteiro](../../plano-testes-manuais.md#3a-scrape-autenticado-do-backend-observabilidade---01), com ambiente isolado, resultados esperados e restauração das permissões temporárias.
- Automatizado: `./mvnw -Dtest=PrometheusSegurancaTest,AutorizacaoTest,UsuarioMeTest,KeycloakAdminClientIntegrationTest test`; regressão completa: `TZ=UTC ./mvnw clean test`.
- Execuções e limitações: [registro de testes](../../testes/observabilidade/observabilidade-01-autenticar-scrape-backend.md).
