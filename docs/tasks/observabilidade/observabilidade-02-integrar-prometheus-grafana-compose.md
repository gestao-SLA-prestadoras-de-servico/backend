# [observabilidade - 02] Integrar Prometheus e Grafana ao Compose

**Requisitos e dependências.** Stack de observabilidade das seções 3.1 e 4.9 de `docs/flowSLA.md`, Docker Compose e credencial `KEYCLOAK_METRICS_CLIENT_SECRET` da [observabilidade-01](observabilidade-01-autenticar-scrape-backend.md).

## Contexto

Os três ambientes passam a usar a mesma definição de Prometheus e Grafana, com portas publicadas em loopback e armazenamento persistente por ambiente. O Prometheus coleta as próprias métricas, disponíveis pelo datasource provisionado do Grafana. A tarefa 3 acrescenta a coleta autenticada do backend. Dashboards, alertas e integração do MS entram nas tarefas correspondentes.

## Critérios de aceite × solução

| Critério | Implementação |
| --- | --- |
| Serviços no Compose base | `infra/docker-compose.yml` fixa as duas imagens, define healthchecks de prontidão do Prometheus e saúde do Grafana e usa `unless-stopped`. |
| Acesso local configurável | `127.0.0.1:19090` para Prometheus e `127.0.0.1:13000` para Grafana, com portas substituíveis pelas variáveis do ambiente. |
| Persistência por ambiente e projeto | `prometheus-dev/test/prod` em `/prometheus` e `grafana-dev/test/prod` em `/var/lib/grafana`, declarados pelos overlays e prefixados pelo Compose. |
| Coleta e retenção | Job `prometheus` a cada 15 s em `infra/prometheus/prometheus.yml` e limites iniciais `15d` e `5GB` configuráveis pelo Compose. |
| Datasource provisionado | UID `prometheus`, endereço `http://prometheus:9090`, proxy e edição desabilitada. |
| Credenciais e acesso | Os secrets chegam como arquivos aos serviços que os usam. A inicialização exige as credenciais configuradas. Cadastro público e acesso anônimo do Grafana ficam desabilitados. |

## Passos técnicos

1. Montados os YAMLs de coleta e provisionamento em modo somente leitura. Os serviços se comunicam pelo DNS da rede Compose.
2. Criados os volumes nos três overlays com escopo de projeto. A rede conserva o nome `flowsla-<ENVIRONMENT>` para os consumidores que já a usam.
3. Adicionados os secrets `metrics_oauth_client_secret` e `grafana_admin_password` ao Compose. O Grafana lê o arquivo via `GF_SECURITY_ADMIN_PASSWORD__FILE`. O Keycloak conserva o repasse da variável de métricas para o import do realm. Os valores sensíveis são sobrescritos por strings vazias no `backend-app`, pois seus overlays carregam os arquivos `.env` completos.
4. Atualizados os três exemplos de ambiente, o README e a seção 3B do roteiro manual.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Imagens | `prom/prometheus:v3.16.0` e `grafana/grafana:13.3.2` | Tags estáveis e fixas no plano aprovado. [Prometheus](https://github.com/prometheus/prometheus/releases/tag/v3.16.0), [Grafana](https://github.com/grafana/grafana/releases/tag/v13.3.2). |
| Retenção | `15d` e `5GB`, com ajuste por ambiente | O primeiro limite atingido determina a retenção. O disco também precisa acomodar WAL, dados ativos e compactação. |
| Ajuste da retenção | Flags do Prometheus suportadas na versão fixada | O Compose interpola as duas variáveis diretamente. As flags estão depreciadas nessa versão, que ainda as usa como valores padrão quando o YAML de coleta omite a configuração de retenção. [Código do Prometheus 3.16.0](https://github.com/prometheus/prometheus/blob/v3.16.0/cmd/prometheus/main.go#L686-L696). |
| Inicialização do Grafana | `start_period` de 360 s antes da contagem de falhas do healthcheck | As migrações do banco do Grafana em um volume novo levaram vários minutos no host de verificação. O serviço pode ficar saudável antes de acabar o período. |
| Segredos | Variáveis de `.envs/` como origem de secrets de Compose | Cada serviço recebe seu arquivo. O Grafana valida que o arquivo da senha tem conteúdo antes de executar o entrypoint oficial da imagem. |
| Verificação | Manual em cópia descartável | O plano aprovado reserva `docker compose config --quiet` e `promtool check config` para execução pelo usuário. |

## Atenção ao atualizar um ambiente existente

Antes de usar o Compose atualizado, preencha `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` e `KEYCLOAK_METRICS_CLIENT_SECRET` no `.env` local do ambiente. Use uma senha própria e portas livres. Para ambientes simultâneos, atribua pares de portas distintos. Em cópias isoladas, escolha também valores de projeto Compose e `ENVIRONMENT` exclusivos.

Preserve os volumes ao recriar containers. O Grafana inicializa a conta no volume vazio. A troca posterior da variável de senha mantém a credencial já gravada. Faça a troca da conta existente pela administração do Grafana. Em realms existentes, a [observabilidade-01](observabilidade-01-autenticar-scrape-backend.md#atenção-ao-atualizar-um-ambiente-existente) descreve a atualização do client no Keycloak.

## Como verificar

- Manual na [seção 3B do roteiro](../../plano-testes-manuais.md#3b-prometheus-e-grafana-no-compose-observabilidade---02).
- Comandos automatizados, resultados esperados e evidências no [registro de testes](../../testes/observabilidade/observabilidade-02-integrar-prometheus-grafana-compose.md).
