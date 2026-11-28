# Testes — [observabilidade - 02] Integrar Prometheus e Grafana ao Compose

**Data** 2026-11-27. **Branch** `feat/observability`.

## Verificação manual

Cópia da infraestrutura em `/tmp`, com Docker Compose 5.1.1, overlay dev, credenciais descartáveis e projeto `flowsla-observabilidade-vgze4h`. Foram iniciados somente os serviços `prometheus` e `grafana`. O [roteiro 3B](../../plano-testes-manuais.md#3b-prometheus-e-grafana-no-compose-observabilidade---02) reproduz a verificação.

| O que foi verificado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Primeira subida, antes do ajuste | `up -d --wait` em volumes novos | Os dois serviços saudáveis | Prometheus saudável. O Compose retornou `container flowsla-observabilidade-vgze4h-grafana-1 is unhealthy`. O Grafana concluiu a inicialização e ficou saudável depois. | Falhou na primeira tentativa |
| Segunda subida, com `start_period: 120s` | `up -d --wait` em volumes novos | Os dois serviços saudáveis | O Compose retornou a mesma falha. O healthcheck do Grafana retornou `curl: (7) Failed to connect to 127.0.0.1:3000` durante as migrações. O Grafana começou a escutar cerca de 6 min e 13 s após iniciar o container. | Falhou antes do ajuste final |
| Subida com a configuração final | `up -d --wait` com `start_period: 360s` e volumes vazios | Os dois serviços saudáveis ao terminar a espera | O Compose concluiu após 389 s com ambos `healthy`. Login, secret, datasource e consulta também passaram nessa subida. | Passou |
| Saúde e acesso local | `compose ps`, `/-/ready` e `/api/health` em loopback | Os dois saudáveis e acessíveis | Ambos `healthy`, `Prometheus Server is Ready.` e `database: ok` no Grafana 13.3.2 | Passou |
| Retenção ativa | `/api/v1/status/runtimeinfo` do Prometheus | `15d` e `5GB` | `15d or 5GiB` | Passou |
| Login | Grafana `POST /login` e `GET /api/user` | Acesso sem sessão recusado e conta administrativa aceita | `401` sem sessão e `isGrafanaAdmin: true` após login | Passou |
| Datasource e coleta | API de datasource e consulta PromQL pelo proxy do Grafana | UID `prometheus`, proxy, URL interna, somente leitura e `up{job="prometheus"}` igual a `1` | UID e proxy esperados, `http://prometheus:9090`, `readOnly: true` e valor `1` | Passou |
| Secrets | `test -r`, `test -s` e `test ! -e` nos serviços | Arquivo próprio legível e não vazio, arquivo do outro serviço ausente | Checks dos dois serviços retornaram código 0, sem exibir conteúdo | Passou |
| Senha inicial inválida | `compose run --rm --no-deps grafana`, uma vez com `GRAFANA_ADMIN_PASSWORD` vazia e outra sem a variável no arquivo | As duas tentativas falham antes de iniciar o servidor | Com valor vazio, o entrypoint pediu `GRAFANA_ADMIN_PASSWORD`. Sem a variável, o Compose informou `environment variable "GRAFANA_ADMIN_PASSWORD" required by secret ... is not set`. | Passou |
| Isolamento e política de reinício | `compose ps`, `compose port` e inspeção de containers e recursos filtrados pelo projeto | Apenas os dois serviços, portas em loopback, rede e volumes exclusivos, `unless-stopped` | `127.0.0.1:36501` e `127.0.0.1:45299`, rede `flowsla-flowsla-observabilidade-vgzE4h`, volumes `<projeto>_prometheus-dev` e `<projeto>_grafana-dev`, política esperada nos dois containers | Passou |
| Persistência após recriação | Salvar o tema `light`, aguardar coleta e executar `up -d --wait --force-recreate` | Novos containers nos mesmos volumes, login e preferência preservados, acesso ao histórico | Com a configuração final, os IDs dos dois containers mudaram, a conta e o tema permaneceram e a consulta histórica pelo Grafana retornou cinco valores `1` anteriores à recriação | Passou |
| Limpeza | Remover o container avulso da tentativa sem senha, executar `down -v` e listar os recursos do projeto | Listagens de containers, rede e volumes vazias | O Compose removeu os dois containers finais, seus volumes e a rede. As três listagens ficaram vazias. | Passou |

## Verificações automatizadas para execução pelo usuário

Execute na raiz do backend. Prepare `.envs/.env.dev`, `.envs/.env.test` e `.envs/.env.prod` a partir dos respectivos exemplos, com credenciais próprias e as variáveis obrigatórias preenchidas. `config --quiet` resolve a configuração sem exibi-la e sem iniciar containers.

```bash
docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml config --quiet
docker compose --env-file .envs/.env.test -f infra/docker-compose.yml -f infra/docker-compose.test.yml config --quiet
docker compose --env-file .envs/.env.prod -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config --quiet

docker run --rm --network none --entrypoint /bin/promtool \
  -v "$PWD/infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro,z" \
  prom/prometheus:v3.16.0 check config /etc/prometheus/prometheus.yml
```

| Verificação | Esperado | Obtido | Status |
| --- | --- | --- | --- |
| `config --quiet`, base + dev | Saída sem modelo resolvido e código 0 | Sem execução | Não verificado |
| `config --quiet`, base + test | Saída sem modelo resolvido e código 0 | Sem execução | Não verificado |
| `config --quiet`, base + prod | Saída sem modelo resolvido e código 0 | Sem execução | Não verificado |
| `promtool check config` | `SUCCESS` para o YAML e código 0 | Sem execução | Não verificado |

## Observações e limitações

- Conforme o plano aprovado, a suíte Maven, scripts de teste e os checks automatizados acima ficaram sem execução. As configurações dos overlays test e prod ficaram sem verificação em execução. Produção ficou fora da verificação manual.
- A configuração de cadastro público foi revisada no Compose. A verificação em execução conferiu o acesso anônimo pela resposta `401` da API do Grafana.
- A coleta autenticada do backend será verificada na tarefa 3. Dashboards, alertas e integração do MS seguem nas respectivas tarefas.
