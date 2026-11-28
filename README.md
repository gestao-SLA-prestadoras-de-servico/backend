# FlowSLA - Backend

Bem-vindo ao repositório de backend do **FlowSLA** - um sistema para automatizar o controle de prazos, evidências e histórico de atendimento relacionados a SLAs (Service Level Agreements), voltado especialmente para empresas prestadoras de serviços técnicos de pequeno e médio porte.

## Qual é o propósito desse repositório?

Este repositório é responsável pelo backend do **FlowSLA**, incluindo as documentações relacionadas especificamente ao backend como mapeamento de endpoints, módulos, entre outros.

## Stack

| Ferramenta                                                                                                 | Versão                              |
| ---------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| [Java](https://docs.oracle.com/en/java/)                                                                   | 21                                  |
| [Spring Boot](https://spring.io/)                                                                          | 4.1.1                               |
| [Spring Data JPA](https://spring.io/projects/spring-data-jpa)                                              | 4.1.1                               |
| [Spring Security](https://spring.io/projects/spring-security)                                              | 7.x                                 |
| [Spring Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html) | 7.x                                 |
| [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)                        | 7.x                                 |
| [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html) | 7.x                    |
| [Spring AMQP (RabbitMQ)](https://spring.io/projects/spring-amqp)                                           | 4.x                                 |
| [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) | 7.x (`spring-boot-starter-restclient`; chamadas HTTP externas) |
| [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)                   | 4.1.1                               |
| [Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)   | 1.17.1                              |
| [Prometheus](https://github.com/prometheus/prometheus/releases/tag/v3.16.0)                                 | 3.16.0 (imagem Docker)              |
| [Grafana](https://github.com/grafana/grafana/releases/tag/v13.3.2)                                          | 13.3.2 (imagem Docker)              |
| [Flyway](https://documentation.red-gate.com/flyway)                                                        | 12.4.0 (+ `flyway-database-postgresql`) |
| [Spring Modulith](https://spring.io/projects/spring-modulith)                                              | 2.1.1 (só verificação de módulos, nos testes) |
| [Resilience4j](https://resilience4j.readme.io/) | 2.4.0 (`resilience4j-spring-boot4`; retry da Admin API do Keycloak, ver autenticacao-03, e do processamento das evidências, ver evidencia-03) |
| [AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html) | 2.59.1 (BOM da AWS; `s3` + `url-connection-client`; cliente do MinIO, ver evidencia-01) |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) | 3.15.3 (`imageio-jpeg` e `imageio-webp`; leitura de JPEG e WebP, ver evidencia-02) |
| [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) | 2.22.0 (orientação do EXIF, ver evidencia-02) |
| [OpenPDF](https://github.com/LibrePDF/OpenPDF) | 3.0.5 (`openpdf` + `openpdf-fonts-extra`; exportação PDF do relatório, ver relatorio-02) |
| [PostgreSQL](https://www.postgresql.org/docs/17/)                                                          | 17 (imagem Docker)                  |
| [Maven](https://maven.apache.org/)                                                                         | -                                   |
| [Testcontainers](https://java.testcontainers.org/)                                                         | ^2.0.5 (PostgreSQL + MinIO + JUnit Jupiter) |
| [ArchUnit](https://www.archunit.org/)                                                                      | 1.4.1 (regras de arquitetura, nos testes) |
| [jqwik](https://jqwik.net/)                                                                                | 1.9.3 (testes por propriedade, ex.: calendário da janela do SLA) |
| [Lombok](https://projectlombok.org/)                                                                       | -                                   |
| [Docker/DockerCompose](https://docs.docker.com/)                                                           | -                                   |
| [DockerCompose](https://docs.docker.com/compose/)                                                          | -                                   |

## Módulos

O backend é um monólito modular. Cada módulo fica em `com.gestaosla.backend.<modulo>`, com dois subpacotes:

- `api`: API pública do módulo. É o único pacote que outros módulos podem importar.
- `internal`: implementação privada do módulo.

| Módulo       | Responsabilidade                                       |
| ------------ | ------------------------------------------------------ |
| `shared`     | Tipos e utilitários comuns a todos os módulos          |
| `identidade` | Autenticação, usuários e permissões                    |
| `cadastro`   | Clientes, contratos e políticas de SLA versionadas     |
| `chamado`    | Abertura e ciclo de vida dos chamados                  |
| `sla`        | Janela comercial, relógios de SLA e prazos             |
| `evidencia`  | Evidências anexadas aos atendimentos                   |
| `outbox`     | Outbox própria para publicação confiável de eventos    |
| `auditoria`  | Histórico e trilha de auditoria                        |
| `relatorio`  | Relatórios e indicadores                               |
| `retencao`   | Políticas de retenção e expurgo de dados               |

O `ModularidadeTest` (`make unit-test`) usa o Spring Modulith (`ApplicationModules.verify()`) e reprova o build se um módulo importar o pacote `internal` de outro ou se houver dependência circular entre módulos.

## Autenticação e autorização

As rotas protegidas exigem `Authorization: Bearer <token do Keycloak>`. Saúde e documentação habilitada em dev são públicas. O token é validado quanto a assinatura, validade, `iss` e `aud` (`app-principal`). Na API de negócio, usuário inativo ou sem registro local recebe 401. O papel vem **sempre** do token (`realm_access.roles` → `ROLE_CLIENTE`/`ROLE_TECNICO`/`ROLE_GESTOR`), nunca da tabela `usuario` (RN-130). Config em `identidade.internal.SegurancaConfig` e `UsuarioCorrenteConverter`.

`/actuator/prometheus` usa uma cadeia stateless exclusiva, prioritária: exige `metrics.read` em `resource_access.flowsla-metrics.roles`, sem consultar `usuario`. A conta `flowsla-metrics` só usa `client_credentials`, recebe a audiência `app-principal` e não recebe papéis de negócio nem de administração. Sem token ou com JWT inválido/expirado: 401; JWT válido sem a permissão desse client: 403; autorizado: 200. Os erros mantêm o formato Problem Details. Token de gestor não permite scrape. A regra fica na cadeia por ser um endpoint do Actuator, sem controller próprio nem `@PreAuthorize`. O scrape interno usa `backend-app:8080/actuator/prometheus`; a publicação da API no host não muda.

O segredo vem de `KEYCLOAK_METRICS_CLIENT_SECRET` em `.envs/.env.<ambiente>` (ver os três `.example`). Na primeira importação, o Keycloak resolve o placeholder no JSON. Em realms existentes, siga a [atualização sem apagar volumes](docs/tasks/observabilidade/observabilidade-01-autenticar-scrape-backend.md#atenção-ao-atualizar-um-ambiente-existente). Obtenção do token e verificações manuais estão na [seção 3A do roteiro](docs/plano-testes-manuais.md#3a-scrape-autenticado-do-backend-observabilidade---01). O Compose já entrega o segredo ao Prometheus, e a coleta autenticada pelo serviço entra na tarefa 3.

Restringir um endpoint por papel tem **dois passos**, os dois obrigatórios:

1. **Regra de rota do módulo** (responde 403 *antes* de o corpo ser lido e validado; sem ela, um não autorizado com corpo inválido receberia 400 e veria o formato do request):

```java
@Bean
RegrasAcesso regrasRelatorio() {   // shared.api.RegrasAcesso, num @Configuration do próprio módulo
    return rotas -> rotas.requestMatchers("/api/relatorios/**").hasRole("GESTOR");
}
```

2. **`@PreAuthorize` no método**, como segunda barreira e documentação de quem pode chamar:

```java
@GetMapping("/relatorios")
@PreAuthorize("hasRole('GESTOR')")                         // papel errado → 403
List<Relatorio> listar(@AuthenticationPrincipal UsuarioCorrente usuario) { // sub, papel e empresa do cliente
```

CORS: só a origem `FRONTEND_URL`. Nos testes, `TokenTeste` assina tokens que passam pelo decoder real (ver `AutorizacaoTest`).

## Documentação da API (OpenAPI)

- **Contrato versionado:** `docs/openapi.json`, gerado do código pelo `OpenApiContratoTest` (`make integration-test`). Se um endpoint mudar, o teste reescreve o arquivo e falha: confira o diff e commite junto. O frontend gera o cliente HTTP a partir desse arquivo.
- **Swagger UI (só dev, `API_DOCS_ENABLED=true`):** http://localhost:8080/swagger-ui.html. Em **Authorize**, o login abre o Keycloak (client `flowsla-swagger`, Authorization Code + PKCE); entre com `gestor`.
- **Novo endpoint:** `@Tag(name = "<Módulo>")` no controller, `@Operation(summary = ...)` no método e `@ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)` para os erros específicos. 400, 401, 403 e 500 entram sozinhos.

## Listagens paginadas

Toda listagem recebe `?page=0&size=20&sort=campo,asc` (máximo de 100 itens por página) e responde no formato `PagedModel` do Spring Data: `{"content": [...], "page": {"size", "number", "totalElements", "totalPages"}}`. No controller: `return new PagedModel<>(pagina.map(Resposta::de));`. Campo de ordenação inexistente dá 400. No serviço, passe a página por `shared.api.Paginas.comDesempate(pagina)`: ela acrescenta o `id` à ordenação, e sem isso itens empatados (dois usuários com o mesmo nome) podem repetir ou sumir entre páginas.

## Edição concorrente

Entidade editada por mais de um usuário devolve `version` no GET e exige essa `version` no PUT. Se outro usuário salvou antes, a resposta é 409 `CONFLITO_VERSAO` (RN-117), em vez de sobrescrever. Exemplo: `cadastro.internal.ClienteService.editar`.

## Auditoria

Operação sensível (RN-121) registra auditoria **na própria transação** (RN-120):

```java
@Transactional
void reabrir(...) {
    // ... altera o chamado
    auditoria.registrar(Ator.usuario(usuario.sub(), usuario.papel()), OperacaoAuditada.REABERTURA,
            EntidadeAuditada.CHAMADO, chamado.getId(), chamado.getId(), Map.of("de", "RESOLVIDO", "para", "ATRIBUIDO"));
}
```

Fora de transação, `registrar` falha; rotinas agendadas usam `Ator.SISTEMA`. A tabela é imutável também no banco: trigger rejeita `UPDATE`, `TRUNCATE` e `DELETE` de registro com menos de 5 anos.

## Eventos para a mensageria (outbox)

Evento destinado ao RabbitMQ é gravado na tabela `outbox_eventos` **na própria transação** da operação (RN-113), nunca publicado direto:

```java
@Transactional
void atribuir(...) {
    // ... altera o chamado
    outbox.registrar(new EventoSaida(null, "ATRIBUICAO", "notificacoes", List.of("interna"),
            Map.of("chamadoId", chamado.getId(), "titulo", chamado.getTitulo())));
}
```

Fora de transação, `registrar` falha. `eventoId` nulo gera um novo; informe um quando ele precisa ser determinístico (violação de SLA, RN-54). Um `eventoId` já gravado é ignorado, sem erro. Cada routing key vira um registro com `eventoId` próprio, acrescentado ao payload. Exchanges e routing keys são as da seção [Topologia do RabbitMQ](#topologia-do-rabbitmq).

O `PublicadorOutbox` (a cada `app.outbox.publicador.intervalo`, 5 s) lê os pendentes em ordem de `criado_em`, em lotes de 100, e publica cada um com confirmação do broker (`publisher-confirm-type=correlated`) e `mandatory=true`. Só marca `publicado_em` depois do confirm. Na falha, o evento fica pendente com `tentativas` e `ultimo_erro`. Broker fora ou confirm que não chega em `espera-confirmacao` param o ciclo. Devolução por falta de fila ou nack seguram só aquele destino (exchange + routing key): os eventos seguintes dele esperam o próximo ciclo e os outros destinos seguem (RN-48). Os lotes são lidos a partir do último evento lido (`(criado_em, id) > ...`). A mensagem sai com `messageId` = `eventoId`, `type` = tipo, JSON e entrega persistente. Entrega ao menos uma vez (RN-115): consumidor descarta repetições pelo `messageId`.

Métricas (RF-64): `outbox_eventos_pendentes` e `outbox_pendente_mais_antigo_idade_seconds`, lidas do banco a cada coleta.

### Notificações

Notificação não usa `EventoSaida` direto: cada tipo é um record de `outbox.api.EventoNotificacao`, e os destinatários saem só do `MontadorDestinatarios` (sub de técnico ou gestor no canal interno, e-mail de contato do cliente no e-mail, RN-41):

```java
outbox.registrar(new EventoNotificacao.Atribuicao(new Chamado(chamado.getId(), chamado.getNumero()), chamado.getTitulo(),
        destinatarios.internos(List.of(tecnicoSub))));
// Violação: eventoId determinístico (RN-54).
outbox.registrar(new EventoNotificacao.Violacao(..., destinatarios.internos(subs), Relogio.RESOLUCAO), eventoId);
```

O tipo define o canal (routing key `interna` ou `email`). Os JSON Schemas de cada tipo ficam em `contratos/notificacoes/`, compartilhados com o ms-notificacoes. São gerados dos records pelo `ContratoNotificacaoTest`, que os reescreve e falha quando o record muda: confira o diff e combine a mudança com a equipe do ms-notificacoes antes de commitar.

## Topologia do RabbitMQ

Exchanges, filas e DLQs ficam em `infra/rabbitmq/topologia.json`, **fonte única** compartilhada com o ms-notificacoes. O `rabbitmq-init` junta esse arquivo com o usuário de `.envs/` no `definitions.json` (ignorado pelo git, porque leva o hash da senha), e o broker o carrega no boot. Assim as filas existem antes de qualquer serviço subir, e nada publicado se perde por falta de fila.

| Exchange (direct) | Routing key | Fila | DLQ (via exchange `.dlx`) |
| --- | --- | --- | --- |
| `notificacoes` | `email` | `notificacoes.email` | `notificacoes.email.dlq` (expira em 7 dias, RN-57) |
| `notificacoes` | `interna` | `notificacoes.interna` | `notificacoes.interna.dlq` (expira em 7 dias, RN-57) |
| `evidencias` | `processamento` | `evidencias.processamento` | `evidencias.processamento.dlq` (sem expiração) |

- **Os serviços não declaram filas, exchanges nem bindings.** No backend, o `TopologiaRabbitArquiteturaTest` reprova qualquer `Declarable` (`Queue`, `Exchange`, `Binding`) ou builder: o `RabbitAdmin` os declararia com argumentos próprios.
- **Mudança na topologia exige aprovação das duas equipes** (backend e ms-notificacoes). Mudar o argumento de uma fila existente no arquivo **não tem efeito**: a importação termina sem erro e a fila fica com o argumento antigo. A fila precisa ser apagada e recriada (ver `docs/tasks/outbox/outbox-02-topologia-rabbitmq.md`).

## Dependência entre módulos e eventos

- **Módulo que ainda não existe:** quem precisa dele declara uma porta no próprio `api` e a injeta como `Optional<Porta>`; o outro módulo a implementa depois, sem mudar quem a usa. Exemplo: `identidade.api.ChamadosDoTecnico`, declarada pelo identidade e implementada depois pelo chamado (`TransferenciaChamados`). Quem implementa a porta numa classe própria evita que um `@MockitoBean` da porta derrube outros beans do módulo.
- **Usuário corrente num módulo de que o `identidade` depende** (ex.: `cadastro`): leia o campo por expressão, `@AuthenticationPrincipal(expression = "empresaClienteId") UUID empresa`, sem importar `UsuarioCorrente`. Importar o tipo cria ciclo entre os módulos, e o `ModularidadeTest` reprova. Exemplo: `cadastro.internal.ContratoController` (`GET /api/me/contratos`).
- **Ouvinte que chama serviço externo** (Keycloak, e-mail) depois do commit: `@Async` + `@TransactionalEventListener`, como em `identidade.internal.ClienteInativadoListener`. Síncrono, ele seguraria a conexão da transação que publicou o evento durante toda a chamada. O evento não é persistido: se a aplicação cair no meio, o que faltou não é refeito (o desligamento normal espera as tarefas terminarem).

## Data e hora

O sistema tem fuso único (`FUSO_HORARIO`, hoje `America/Fortaleza`), usado pela JVM, pela sessão JDBC, pelo Hibernate e pelo Postgres. Quem precisa da hora injeta `java.time.Clock` (bean do `shared`); `Instant.now()`, `LocalDateTime.now()` e similares sem o Clock reprovam o `RelogioArquiteturaTest`. Nos testes, `RelogioMutavel` substitui o Clock e avança o tempo sem esperar.

## Prazos de SLA

Todo prazo é contado em minutos da janela comercial (segunda a sexta, 08h às 17h, no fuso do sistema; feriado conta como dia útil). Some e meça sempre pelo `sla.api.CalendarioJanela` (`adicionarMinutos`, `minutosUteisEntre`), nunca com `plusMinutes` ou `Duration` direto. Os relógios do chamado ficam em `sla.api.RelogiosSla`: os métodos de escrita rodam na transação de quem chama e devolvem as violações recém-marcadas (`Violacao`), que o chamador grava na outbox. Mudança de prioridade usa `recalcularPorPrioridade`; reabertura, `reiniciarParaReabertura`; o encerramento congela o resultado em `sla.api.ApuracaoSla.congelarApuracao`, que nunca muda depois. A versão da política passada ao sla é sempre a guardada no chamado (`ContratoConsulta.versaoDaPolitica`). O `SlaArquiteturaTest` impede o sla de depender do chamado.

## Chamado

O estado do chamado só muda por `chamado.internal.TransicaoChamado.transicionar`: a entidade valida a transição (RN-10, `TRANSICAO_INVALIDA` fora dela) e a auditoria `MUDANCA_ESTADO` e o histórico são gravados na mesma transação. O histórico (`HistoricoChamado`) é a linha do tempo dos três perfis, com a visibilidade definida em `TipoEventoHistorico`; a auditoria continua só do gestor. Toda `Violacao` devolvida pelo sla vai para `NotificadorViolacao.notificar`, o único que grava `VIOLACAO` na outbox (gestores ativos + técnico atribuído, `eventoId` determinístico). Atendimentos: `AtendimentoService.criarPendente` e `interromperCorrente`, com no máximo um corrente por chamado (índice único parcial); o evidencia lê o corrente por `chamado.api.AtendimentoConsulta`.

Trocar o técnico (atribuição, reatribuição e transferência por inativação) passa por `AtribuicaoService.trocarTecnico`: interrompe o atendimento corrente, avisa o novo técnico e nunca toca os relógios (RN-22). O primeiro posicionamento (`PosicionamentoService`) cumpre a RESPOSTA e grava o e-mail ao cliente na mesma transação. O início do atendimento (`AtendimentoService.iniciar`) cumpre o CAMPO; o cancelamento (`CancelamentoService`) interrompe o atendimento e não avisa o técnico. A preferência de notificação de conclusão é por gestor e por chamado (`PreferenciaNotificacaoService.gestoresComPreferencia`).

Rotinas agendadas do chamado: `RotinaViolacao` (`app.chamado.rotina-violacao.cron`, a cada 15 min) marca os relógios vencidos pelo `RelogiosSla.travarVencidos` e `marcarViolacao`, avisa pelo `NotificadorViolacao`, grava o histórico `VIOLACAO_SLA` e conta `sla_violacoes_total{relogio}`. `RotinaValidacaoAutomatica` (`app.chamado.validacao-automatica.cron`, de hora em hora) avisa o cliente 1080 minutos de janela depois da resolução e encerra aos 1620, pelo `ValidacaoService.encerrar`, com a auditoria `VALIDACAO_AUTOMATICA`. Padrão das rotinas: uma transação por lote (`TransactionTemplate`), lote lido com `PESSIMISTIC_WRITE` e `SKIP LOCKED` (o registro que o usuário está alterando fica para a passada seguinte) e cursor keyset, para o que é pulado não voltar no mesmo ciclo; ator `Ator.SISTEMA`. Nos testes o cron é `-` (desligado) e o `ChamadoTestHelper` roda uma passada com o lote que o teste quiser.

Consultas de chamado passam sempre pelo escopo do usuário (`ConsultaChamadoService`): gestor vê todos, cliente os da própria empresa, técnico os atribuídos a ele agora; fora do escopo é 404 (RN-126), e parâmetros como `clienteId` não ampliam nada. Outro módulo que precise saber se o usuário vê um chamado usa `chamado.api.AcessoChamado.podeAcessar`. Cada perfil tem a sua visão (`ChamadoGestor`, `ChamadoCliente`, `ChamadoTecnico`); a do técnico nunca leva contrato nem política.

## Retenção e expurgo

A rotina roda diariamente às 02:00 em `FUSO_HORARIO`. A propriedade Spring
`app.retencao.cron` permite ajustar o horário, por exemplo com o argumento
`--app.retencao.cron="0 0 3 * * *"`. O valor `-` desativa o agendamento.
Cada passo trabalha em lotes de 100 e registra itens removidos, falhas e duração
nas métricas `retencao.itens`, `retencao.falhas` e `retencao.duracao`, com a tag `passo`.
A rotina remove da outbox eventos publicados há mais de sete dias e da auditoria
registros com mais de cinco anos de calendário. O trigger da auditoria continua
validando cada exclusão.
O passo de evidências expurga o conteúdo de chamados encerrados ou cancelados há
mais de 180 dias e registra a operação na auditoria. A linha da evidência permanece
com seus metadados e a data do expurgo.
A geração de derivados e o expurgo usam a mesma trava de linha no PostgreSQL
durante o acesso ao MinIO. Assim, o processamento de uma mensagem atrasada
respeita o estado `EXPURGADA`.
O passo de órfãos percorre o bucket `evidencias` em páginas. Objetos sem
vínculo ou sem chave registrada são removidos após 24 horas. O passo preserva
os objetos de evidências `RECEBIDA` ou `PROCESSANDO`.

## Storage das evidências (MinIO)

O bucket `evidencias` é criado na subida (`ApplicationReadyEvent`) se não existir. Se o MinIO estiver fora do ar nesse momento, a aplicação só registra um aviso e sobe; o primeiro uso falha com 503 `SERVICO_INDISPONIVEL`. Todo acesso passa por `evidencia.internal.ArmazenamentoEvidencias` (gravar, ler em stream, listar com data de modificação, remover prefixo), que traduz erro de rede, timeout e 5xx em `ServicoIndisponivelException`. As chaves seguem `ChaveObjeto`: `evidencias/{chamadoId}/{evidenciaId}/original.{ext}`, `.../miniatura.jpg` e `.../visualizacao.jpg`. `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY` e `MINIO_SECRET_KEY` vêm de `.envs/` (por ora o usuário root; usuário dedicado é pendência) e os timeouts ficam em `app.armazenamento.*`. Nos testes, o `MinioTeste` sobe um MinIO real por JVM e o `TestcontainersConfiguration` aponta todo contexto para ele.

## Recepção de evidências

`POST /api/chamados/{id}/evidencias` (multipart, campo `arquivo`, só o TECNICO atribuído) é feito por `evidencia.internal.RecepcaoEvidenciaService`, nesta ordem: confere o acesso (`AcessoChamado`: não atribuído é 404) e o atendimento EM_ANDAMENTO (`AtendimentoConsulta`, senão 422 `TRANSICAO_INVALIDA`); sanitiza; grava o objeto no MinIO **fora de transação**; e abre uma transação que refaz a conferência e grava a evidência `RECEBIDA`, a auditoria `ENVIO_EVIDENCIA` e o evento `PROCESSAR_EVIDENCIA` na outbox (só a referência ao objeto, nunca o binário). Se a transação falhar, o objeto fica órfão no bucket, para a rotina de retenção remover (RN-112); a resposta é 500. A resposta de sucesso é 202 com `{id, estado: "RECEBIDA"}`.

A `SanitizadorImagem` decide o tipo pelos bytes (JPEG, PNG ou WebP; nome e Content-Type do cliente não contam), confere as dimensões no cabeçalho contra `app.evidencia.teto-megapixels` (40; botão de calibração: 40 MP decodificados ≈ 160 MB de heap) antes de decodificar, aplica a orientação do EXIF aos pixels e regrava a imagem sem nenhum metadado: JPEG e WebP saem em JPEG (qualidade 0,92), PNG sai em PNG. O hash SHA-256 é dos bytes finais e é imutável (trigger no banco). O limite de 10 MB é do multipart (`spring.servlet.multipart.max-file-size`), com a mesma conferência no serviço. A entidade `Evidencia` não estende a `EntidadeBase` porque o id (UUID v7) é gerado antes, para compor a chave do objeto. A contagem por atendimento no detalhe do chamado vem de `ContagemEvidencias`, que implementa `chamado.api.EvidenciasDoChamado`.

## Processamento das evidências

`evidencia.internal.ProcessadorEvidencias` é o `@RabbitListener` da fila `evidencias.processamento` (consumidor interno, RN-84). Concorrência e prefetch vêm das propriedades do Boot (`spring.rabbitmq.listener.simple.concurrency=1`, `max-concurrency=2`, `prefetch=1`): é o único listener do app; se surgir outro, ele ganha fábrica própria. A mensagem só carrega o `evidenciaId` (o resto vem do banco). O `GeradorDerivados` reivindica a evidência com um `UPDATE` condicional (`RECEBIDA` ou `PROCESSANDO` vira `PROCESSANDO`; qualquer outro estado, ou id inexistente, é descartado com ack e sem processar, RN-86/RN-115), lê o original do MinIO, gera a miniatura (lado maior 320 px) e a visualização (1280 px) em JPEG 0,85 pelo `Redimensionador` (Java2D, sem ampliar, fundo branco sob transparência) e grava as chaves e `DISPONIVEL` num `UPDATE` que comita antes de o método do listener retornar: o ack (automático) só sai depois (RN-90). O original nunca é alterado (RN-78).

O retry é do Resilience4j (`@Retry(name = "evidencias")` no `GeradorDerivados`, bean separado do listener porque o retry é por proxy): 3 tentativas (a primeira conta), backoff de 1 s e 2 s, só em `ServicoIndisponivelException` e falhas transitórias de banco. Imagem que não decodifica não é repetida. Esgotado o retry, ou em qualquer outra exceção, o `ProcessadorEvidencias` marca a evidência `FALHA` e lança `AmqpRejectAndDontRequeueException`: sem requeue, a DLX da topologia a leva à `evidencias.processamento.dlq` (RF-61). `default-requeue-rejected=false` é a rede de segurança. `FALHA` não toca no atendimento nem no chamado (RN-89). Métrica: `evidencias{estado=...}` (um gauge por estado, lido do banco). Nos testes o listener vem desligado (`auto-startup=false`); só o `ProcessadorEvidenciasTest` o liga.

## Listagem e download das evidências

`GET /api/chamados/{id}/evidencias` lista as evidências agrupadas por atendimento e ordenadas por `enviadaEm`, com id, nome, tipo, tamanho, hash, estado e datas. Apenas a listagem do gestor inclui evidências `EXPURGADA`, sem miniatura. `GET /api/evidencias/{id}`, exclusivo do gestor, retorna id, atendimento, chamado, envio, expurgo, hash e estado sem ler o MinIO (RF-46).

`GET /api/evidencias/{id}/conteudo?variante=visualizacao|miniatura|original` (padrão `visualizacao`) serve a imagem. As consultas passam por `AcessoChamado.podeAcessar` (`ConsultaEvidenciaService`), com 404 para recurso inexistente ou fora do escopo (RN-126). O conteúdo de evidência `EXPURGADA` responde 410 `EVIDENCIA_EXPURGADA`. Se o derivado pedido ainda não existe (`RECEBIDA`, `PROCESSANDO`, `FALHA`), serve o original (RN-81).

**Padrão de download por streaming** (`EvidenciaController.conteudo`): o objeto é aberto no MinIO **antes** de a resposta sair (falha do storage ainda vira 404/503 normal e o tamanho vai no `Content-Length`), e o corpo é um `StreamingResponseBody` que faz `transferTo` do `InputStream` do storage para a saída e o fecha (`try-with-resources`, inclusive se o cliente abortar). O arquivo nunca é carregado inteiro na memória: no teste de 10 MB o stream do storage trava depois de ~1 MB e o cliente já recebe os primeiros bytes. O `StreamingResponseBody` roda em outra thread: nada de acesso lazy do JPA dentro da lambda (`open-in-view=false`). Cabeçalhos: `Content-Type` (`image/jpeg` para derivado, o tipo gravado para o original), `Content-Length`, `ETag` forte `"{hash}-{variante servida}"` (o hash é do original; a variante distingue os bytes), `Cache-Control: private, no-cache` (o navegador guarda mas revalida sempre com `If-None-Match`; sem `max-age`, quem perdeu o acesso recebe 404 na revalidação) e `Content-Disposition: inline` (o Spring Security já põe `X-Content-Type-Options: nosniff`). `If-None-Match` igual responde 304 **sem abrir o objeto**, mas só depois da conferência de acesso. Nos testes com `MockMvc`, o corpo exige `asyncDispatch`.

## Reprocessamento de evidência

`POST /api/evidencias/{id}/reprocessamento` (GESTOR) é do `ReprocessamentoService`: numa transação, `UPDATE` condicional `FALHA` → `RECEBIDA` (0 linhas = outro estado ou outro gestor chegou antes: 422 `REPROCESSAMENTO_NAO_PERMITIDO`), auditoria `REPROCESSAMENTO` (só estado de/para, sem dado pessoal) e um `PROCESSAR_EVIDENCIA` novo na outbox, com `eventoId` novo (o evento é montado em `RecepcaoEvidenciaService.eventoProcessamento`, o mesmo da recepção). A mensagem antiga que está na DLQ, se reinjetada à mão, acha a evidência já `DISPONIVEL` e é descartada (idempotência da evidencia-03).

## Erros da API

Todo erro sai como Problem Details (RFC 9457) com o campo `codigo`, tratado no `shared.internal.GlobalExceptionHandler`. Regra de negócio: `throw new RegraNegocioException(CodigoErro.X, "mensagem")` (422); recurso inexistente ou de outro cliente: `throw new RecursoNaoEncontradoException()` (404). Status, códigos e catálogo em `docs/erros-api.md`.

## Como executar o projeto:

> [!IMPORTANT]
>
> Requisitos:
>
> - Docker
> - Docker Compose 2.30 ou mais recente

O projeto está dividido em três ambientes (desenvolvimento, testes e produção), abaixo estão as informações de como rodar cada um deles:

### Ambiente de Desenvolvimento

#### 1. Copie as variáveis de ambiente de desenvolvimento:

```bash
cp .envs/.env.dev.example .envs/.env.dev
```

#### 2. Rode o projeto via docker compose:

```bash
make run-dev
```

O ambiente sobe o backend, suas dependências e os serviços de observabilidade. O backend espera pelas dependências declaradas no Compose.

| Serviço    | Acesso em dev                                  | Observação                                                                 |
| ---------- | ---------------------------------------------- | -------------------------------------------------------------------------- |
| Backend    | http://localhost:8080                          | `/actuator/health`                                                         |
| PostgreSQL | só rede interna (`postgres:5432`)              | bancos `app_principal`, `notificacoes` e `keycloak`, um usuário por banco (`infra/postgres/init.sql`) |
| RabbitMQ   | http://localhost:15672 (management)            | topologia de `infra/rabbitmq/topologia.json`                               |
| MinIO      | http://localhost:9001 (console)                | a porta S3 (9000) não é publicada no host                                  |
| Keycloak   | http://localhost:8081                          | console admin em `KEYCLOAK_ADMIN_USER`/`KEYCLOAK_ADMIN_PASSWORD`; realm `flowsla` importado de `infra/keycloak/realm-flowsla.json` |
| Mailpit    | http://localhost:8025                          | caixa de e-mails de teste                                                  |
| Prometheus | http://127.0.0.1:19090                         | coleta as próprias métricas a cada 15 s                                   |
| Grafana    | http://127.0.0.1:13000                         | datasource `prometheus` provisionado via rede interna                     |
| ms-notificacoes | http://localhost:8082                     | só com `make run-dev` (profile `ms`), construído de `../ms-notifications`; o `ci-run-dev` não o sobe |

Em test e prod, as duas portas de observabilidade são publicadas em `127.0.0.1`. Os serviços ficam na rede `flowsla-<ENVIRONMENT>`, que o ms-notificacoes usa como rede externa para acessar o PostgreSQL e o RabbitMQ compartilhados.

#### Credenciais de desenvolvimento (realm `flowsla`)

| O quê | Valor em dev | Uso |
| --- | --- | --- |
| Gestor inicial | usuário `gestor` (`gestor@flowsla.local`), senha `KEYCLOAK_GESTOR_PASSWORD` | primeiro login; cadastra os demais usuários. O `sub` é fixo e o mesmo da `V2__cria_usuario.sql` |
| Client do frontend | `flowsla-frontend` (público, Authorization Code + PKCE S256) | redirect em `FRONTEND_URL/*`; o token sai com `aud` `app-principal` e `ms-notificacoes` e com `realm_access.roles` |
| Client do Swagger UI | `flowsla-swagger` (público, PKCE S256) | redirect só em `SWAGGER_URL/swagger-ui/oauth2-redirect.html`; mesmos `aud` do frontend |
| Conta de serviço | client `app-principal-admin`, segredo `KEYCLOAK_ADMIN_CLIENT_SECRET` | Admin API; só `manage-users`, `view-users` e `view-realm` |
| Conta de métricas | client `flowsla-metrics`, segredo `KEYCLOAK_METRICS_CLIENT_SECRET` | somente `metrics.read` do próprio client; leitura de `/actuator/prometheus`, sem cadastro local |
| E-mails do Keycloak | SMTP `SMTP_HOST:SMTP_PORT` (Mailpit) | ver em http://localhost:8025 |

O backend valida o token em `KEYCLOAK_ISSUER_URI` (o `iss`) e busca as chaves em `KEYCLOAK_JWK_SET_URI`. `GET /api/usuarios/me` devolve o usuário do token. `GET /api/usuarios` e `GET /api/tecnicos` (só GESTOR) leem a cópia local do perfil: servem para consulta, nunca para autorizar (RN-130).

> [!WARNING]
> O realm só é importado se ainda não existir no banco do Keycloak. Alterar `realm-flowsla.json` e reiniciar não atualiza realms existentes. Inclua os recursos pelo console administrativo ou Admin API, preservando os dados; para `flowsla-metrics`, siga a [observabilidade-01](docs/tasks/observabilidade/observabilidade-01-autenticar-scrape-backend.md#atenção-ao-atualizar-um-ambiente-existente). Não use `make run-dev-down` para atualizar o realm: ele apaga os volumes de dev.

> [!WARNING]
> O `init.sql` só roda com o volume do PostgreSQL vazio. Se o ambiente já foi criado antes, rode `make run-dev-down` (apaga os dados de dev) antes de subir de novo.

### Ambiente de Testes

#### 1. Copie as variáveis de ambiente de testes:

```bash
cp .envs/.env.test.example .envs/.env.test
```

#### 2. Rode o projeto via docker compose:

```bash
make run-test
```

### Ambiente de Produção

#### 1. Copie as variáveis de ambiente de produção:

```bash
cp .envs/.env.prod.example .envs/.env.prod
```

#### 2. Rode o projeto via docker compose:

```bash
make run-prod
```

### Observabilidade no Compose

Prometheus e Grafana usam `unless-stopped` e têm volumes de dados próprios nos três ambientes. O Compose nomeia os volumes como `<projeto>_prometheus-<ambiente>` e `<projeto>_grafana-<ambiente>`. Para rodar ambientes no mesmo host, escolha duas portas livres diferentes para cada um e use valores distintos de `ENVIRONMENT` e de projeto Compose em cópias isoladas.

| Variável em `.envs/.env.<ambiente>` | Valor inicial ou uso |
| --- | --- |
| `PROMETHEUS_LOCAL_PORT` | `19090`, porta de `127.0.0.1` para `prometheus:9090` |
| `GRAFANA_LOCAL_PORT` | `13000`, porta de `127.0.0.1` para `grafana:3000` |
| `PROMETHEUS_RETENTION_TIME` | `15d`, retenção por tempo |
| `PROMETHEUS_RETENTION_SIZE` | `5GB`, retenção por tamanho |
| `GRAFANA_ADMIN_USER` | `admin` nos exemplos, nome obrigatório para a conta inicial |
| `GRAFANA_ADMIN_PASSWORD` | Senha inicial obrigatória. Substitua o valor ilustrativo por uma senha própria. |
| `KEYCLOAK_METRICS_CLIENT_SECRET` | Segredo obrigatório do client `flowsla-metrics`, reutilizado da tarefa 1. |

Vale o primeiro limite de retenção atingido. Reserve espaço adicional para o WAL, os dados ativos e a compactação. O datasource `prometheus` usa `http://prometheus:9090` via proxy e tem edição desabilitada. Cadastro público e acesso anônimo do Grafana ficam desabilitados.

O Compose usa o arquivo passado em `--env-file` como origem dos secrets. Entrega `KEYCLOAK_METRICS_CLIENT_SECRET` ao Prometheus em `/run/secrets/metrics_oauth_client_secret` e `GRAFANA_ADMIN_PASSWORD` ao Grafana em `/run/secrets/grafana_admin_password`, apontado por `GF_SECURITY_ADMIN_PASSWORD__FILE`. O Keycloak continua recebendo o segredo de métricas por variável para a importação do realm. Em ambientes existentes, adicione as variáveis ao arquivo local antes de subir o Compose atualizado.

A conta e a senha iniciais do Grafana ficam persistidas no volume. Alterar `GRAFANA_ADMIN_PASSWORD` no `.env` e recriar o container mantém a senha da conta existente. Faça a troca pela administração do Grafana. A verificação dos dois serviços em uma cópia isolada está na [seção 3B do roteiro](docs/plano-testes-manuais.md#3b-prometheus-e-grafana-no-compose-observabilidade---02).

## Como executar os testes:

> [!IMPORTANT]
>
> Requisitos:
>
> - Docker (para testes de integração)

> Obs.: O Docker é necessário, pois os testes de integração utilizam Testcontainers.

### Testes unitários:

> Não requerem docker

```bash
make unit-test
```

### Testes de integração:

```bash
make integration-test
```

## Documentação por task

Toda task entregue gera dois arquivos em `docs/`, com o mesmo nome, numa pasta por módulo (`<modulo>/<modulo>-<nn>-<descricao>.md`, ex.: `plataforma/plataforma-03-padrao-schema-banco.md`):

- `docs/tasks/`: contexto, tabela critério de aceite × solução, passos técnicos realizados, decisões e cuidados ao atualizar um ambiente existente.
- `docs/testes/`: data e branch, tabela com o que foi testado, como, resultado esperado, resultado obtido e status, e o que ficou sem verificar.

Convenções transversais ficam em arquivo próprio em `docs/` (ex.: `docs/convencoes-banco.md`).

## Estrutura de pastas

```bash
.
├── contratos
│   └── notificacoes # JSON Schemas das mensagens de notificação, gerados pelo ContratoNotificacaoTest
├── docs # Documentação das tasks do backend
│   ├── convencoes-banco.md # Padrão de schema e migrações
│   ├── erros-api.md # Formato dos erros e catálogo de códigos
│   ├── openapi.json # Contrato da API, gerado pelo OpenApiContratoTest
│   ├── tasks # O que cada task resolveu, uma pasta por módulo
│   └── testes # Testes executados em cada task, uma pasta por módulo
├── Dockerfile
├── .dockerignore
├── .envs # Variáveis de ambiente do projeto
│   ├── .env.dev.example
│   ├── .env.prod.example
│   └── .env.test.example
├── .gitattributes
├── .github # Configurações do GitHub
│   └── workflows # GitHub Actions (CI/CD)
│       └── ci.yml
├── .gitignore
├── infra # Arquivos relacionados à infraestrutura
│   ├── keycloak
│   │   └── realm-flowsla.json # Realm importado na subida (papéis, clients, gestor inicial, SMTP)
│   ├── postgres
│   │   └── init.sql # Bancos e usuários do PostgreSQL compartilhado
│   ├── rabbitmq
│   │   ├── generate_definitions.py # Gera o definitions.json (usuário de .envs + topologia) a cada subida
│   │   ├── rabbitmq.conf # Carrega o definitions.json no boot
│   │   ├── topologia.json # Exchanges, filas e DLQs; fonte única com o ms-notificacoes
│   │   └── enabled_plugins
│   ├── docker-compose.dev.yml
│   ├── docker-compose.prod.yml
│   ├── docker-compose.test.yml
│   └── docker-compose.yml
├── LICENSE
├── Makefile # Atalhos para comandos e scripts
├── .mvn
│   └── wrapper
├── mvnw
├── mvnw.cmd
├── pom.xml # Dependências e configuração do Maven
├── README.md
├── src # Código-fonte e testes
│   ├── main
│   └── test
└── .vscode # Configuração do workspace no VSCode
    └── settings.json
```

<!-- ADICIONAR MAPEAMENTO DE ENDPOINTS -->

<!-- ADICIONAR TABELA EXPLICATIVA DA DOCUMENTAÇÃO DO BACKEND -->

## Repositórios relacionados

| Repositório                                                       | Função             |
| ----------------------------------------------------------------- | ------------------ |
| [docs](https://github.com/gestao-SLA-prestadoras-de-servico/docs) | Documentação geral |

## Autores

|                                                                                                                         |                                                                                                                                |                                                                                                                                     |
| :---------------------------------------------------------------------------------------------------------------------: | :----------------------------------------------------------------------------------------------------------------------------: | :---------------------------------------------------------------------------------------------------------------------------------: |
|                <img src="https://github.com/genzo-dev.png" alt="Gabriel Enzo (genzo-dev)" width="160"/>                 |                        <img src="https://github.com/LuisF3L1P3dev.png" alt="Luis Felipe" width="160"/>                         |                                <img src="https://github.com/rangelro.png" alt="Rangel" width="160"/>                                |
|                                              **Gabriel Enzo (genzo-dev)**                                               |                                                        **Luis Felipe**                                                         |                                                             **Rangel**                                                              |
|                                           <sub>Fullstack web developer</sub>                                            |                                               <sub>Fullstack web developer</sub>                                               |                                                 <sub>Fullstack web developer</sub>                                                  |
| <a href="https://www.linkedin.com/in/genzo-dev/">💼 LinkedIn</a> · <a href="https://github.com/genzo-dev">🐙 GitHub</a> | <a href="https://www.linkedin.com/in/luisfelipe15/">💼 LinkedIn</a> · <a href="https://github.com/LuisF3L1P3dev">🐙 GitHub</a> | <a href="https://www.linkedin.com/in/rangel-rocha-779139228/">💼 LinkedIn</a> · <a href="https://github.com/rangelro">🐙 GitHub</a> |
