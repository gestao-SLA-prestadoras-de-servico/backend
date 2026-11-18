# NIVELO — Tasks de Retenção e Expurgo (RETENÇÃO-01 a RETENÇÃO-05)

> **Projeto:** [gestao-SLA-prestadoras-de-servico/backend](https://github.com/gestao-SLA-prestadoras-de-servico/backend)  
> **Fonte:** issues #74 a #78 do GitHub, consultadas em 18/11/2026.  
> **Objetivo:** reunir o texto original das cinco issues e acrescentar instruções de execução para uma IA de desenvolvimento.  
> **Importante:** as seções **Descrição original da issue** reproduzem os conteúdos das issues do GitHub. As seções **Orientações para implementação com IA** são recomendações adicionais, e não novos requisitos oficiais.

## Sumário

| Task | Título | Issue | Dependências |
|---|---|---|---|
| RETENÇÃO-01 | Criar a rotina diária de retenção e limpar a outbox | [#74](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/74) | plataforma-04, outbox-01 |
| RETENÇÃO-02 | Expurgar evidências vencidas | [#75](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/75) | retenção-01, evidência-01, auditoria-01 |
| RETENÇÃO-03 | Expurgar auditoria com mais de 5 anos | [#76](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/76) | retenção-01, auditoria-01 |
| RETENÇÃO-04 | Remover objetos órfãos do storage | [#77](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/77) | retenção-01, evidência-01, evidência-02 |
| RETENÇÃO-05 | Consultar evidência expurgada | [#78](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/78) | retenção-02 |

## Instruções gerais para a IA

1. **Inspecione o projeto antes de alterar qualquer arquivo.** Identifique a estrutura modular, as implementações dos módulos `retencao`, `outbox`, `evidencia`, `auditoria` e `chamado`, as migrations, as interfaces públicas e os testes existentes.
2. **Valide as dependências.** Se algum módulo ou contrato necessário ainda não estiver implementado, registre o bloqueio e não invente interfaces incompatíveis. Não declare uma task concluída sem passar pelos seus critérios de aceite.
3. **Preserve os limites dos módulos.** Utilize contratos públicos (`api`); não importe implementações `internal` de outros módulos.
4. **Implemente retenção de forma segura.** Utilize lotes e transações controladas, isolamento entre etapas, tratamento de reexecuções, métricas e logs sem dados pessoais. Não crie endpoints de expurgo manual.
5. **Teste somente em ambiente isolado com dados sintéticos.** Não apague volumes, objetos ou registros reais ou compartilhados. Nunca use `docker compose down -v` em ambientes existentes.
6. **Mantenha rastreabilidade por issue.** Execute os testes correspondentes e reporte os resultados e arquivos alterados. Não faça commit, push, merge ou mudanças no GitHub sem autorização.
7. **Preserve os requisitos originais.** Se houver decisões técnicas ainda não definidas (por exemplo, coordenação de múltiplas réplicas, fuso do agendamento ou paginação), documente a decisão e verifique a compatibilidade com a arquitetura do projeto.

---

# RETENÇÃO-01 — Criar a rotina diária de retenção e limpar a outbox

**Issue:** [#74](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/74)  
**Atribuição no GitHub:** `LuisF3L1P3dev`  
**Status no Kanban fornecido:** Backlog

## Descrição original da issue

### Contexto e Objetivo
A Rotina de Retenção e Expurgo roda uma vez por dia, fora do horário comercial, e executa quatro passos independentes: falha em um não impede os outros. O primeiro passo é o mais simples: apagar eventos da outbox já publicados há mais de 7 dias. Nenhum perfil consegue disparar expurgo manualmente.

**Requisitos:** RF-42, RF-48, RN-108, RN-110, RN-119  
**Depende de:** [plataforma - 04], [outbox - 01]

### Critérios de Aceite (Definition of Done)
- [ ] A rotina roda diariamente em horário configurável (ex.: 02:00).
- [ ] Eventos publicados há mais de 7 dias são removidos.
- [ ] Evento pendente nunca é apagado, por mais antigo que seja.
- [ ] Falha em um passo não impede os demais.
- [ ] Não existe endpoint para disparar o expurgo.
- [ ] Testes de integração com relógio avançado criados e passando.

### Passos Técnicos / Checklists
- [ ] Criar `RotinaRetencao` com `@Scheduled` e um passo por componente
- [ ] Criar o passo `LimpezaOutbox`, em lotes com uma transação por lote
- [ ] Criar métricas por passo (itens, falhas, duração)

## Orientações para implementação com IA

- Localizar o relógio injetável do projeto e o contrato do módulo outbox; evitar consultas diretas ao relógio do sistema quando o projeto já dispõe de abstração.
- Criar o agendador configurável e estruturar cada passo para que uma falha não impeça as demais tarefas de retenção.
- Selecionar **somente eventos publicados** com mais de sete dias; preservar eventos pendentes independentemente da idade.
- Executar exclusões em lotes com transações independentes e instrumentar quantidade, falhas e duração.
- Testar as fronteiras de tempo, eventos pendentes e falha isolada de cada etapa.
- **Atenção:** os outros três passos pertencem às issues seguintes; criar extensibilidade sem fingir que já estão implementados.

**Evidência esperada:** resultados dos testes de integração, conferência de eventos publicados e pendentes e métricas por passo.

---

# RETENÇÃO-02 — Expurgar evidências vencidas

**Issue:** [#75](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/75)  
**Status no Kanban fornecido:** Backlog

## Descrição original da issue

### Contexto e Objetivo
As imagens são o dado mais caro de guardar e são dado pessoal. 180 dias depois de o chamado ficar `ENCERRADO` ou `CANCELADO`, o conteúdo é apagado do storage, mas o registro continua no banco para provar que a evidência existiu.

**Requisitos:** RF-43, RF-45, RN-100 a RN-105  
**Depende de:** [retenção - 01], [evidência - 01], [auditoria - 01]

### Critérios de Aceite (Definition of Done)
- [ ] Só são elegíveis evidências de chamados com `estado_final_em` há mais de 180 dias.
- [ ] Chamado `RESOLVIDO` há 1 ano não tem evidência expurgada.
- [ ] Original e todos os derivados são removidos; listar o prefixo no MinIO retorna vazio.
- [ ] O registro fica `EXPURGADA`, com id, atendimento, chamado, data de envio e data do expurgo.
- [ ] A auditoria registra o expurgo com motivo `EXPIRACAO_RETENCAO` e ator `SISTEMA`.
- [ ] Rodar de novo depois de uma falha no meio não gera erro.

### Passos Técnicos / Checklists
- [ ] Criar a consulta de evidências elegíveis (a data final vem da API do módulo `chamado`)
- [ ] Remover o prefixo no MinIO e depois atualizar o banco, em lotes
- [ ] Registrar a auditoria

## Orientações para implementação com IA

- Obter o estado final e a data `estado_final_em` pela **API pública do módulo `chamado`**, sem consultar suas entidades internas.
- Considerar apenas chamados `ENCERRADO` ou `CANCELADO` com encerramento definitivo há mais de 180 dias; `RESOLVIDO` não é elegível.
- Excluir o original e todos os derivados do MinIO antes de registrar o estado `EXPURGADA`.
- Preservar os metadados exigidos e registrar auditoria com motivo `EXPIRACAO_RETENCAO` e ator `SISTEMA`.
- Tornar a reexecução segura após falhas intermediárias; usar lote e infraestrutura de teste isolada.
- Testar a ausência de objetos no prefixo, a preservação dos metadados e a auditoria.

**Evidência esperada:** teste de limite de 180 dias, listagem vazia de original/derivados e registro persistido como `EXPURGADA`.

---

# RETENÇÃO-03 — Expurgar auditoria com mais de 5 anos

**Issue:** [#76](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/76)  
**Status no Kanban fornecido:** Backlog

## Descrição original da issue

### Contexto e Objetivo
A trilha de auditoria é guardada por 5 anos e depois removida, só por esta rotina. O trigger do banco é a garantia final de que um registro mais novo nunca sai, nem por engano.

**Requisitos:** RF-44, RN-106, RN-107  
**Depende de:** [retenção - 01], [auditoria - 01]

### Critérios de Aceite (Definition of Done)
- [ ] Registros com mais de 5 anos são removidos em lotes.
- [ ] Registro com 4 anos e 364 dias permanece.
- [ ] A quantidade removida aparece em log e em métrica.

### Passos Técnicos / Checklists
- [ ] Criar o passo `ExpurgoAuditoria` com `DELETE` em lotes
- [ ] Criar o teste que confirma o bloqueio pelo trigger

## Orientações para implementação com IA

- Inspecionar a regra de retenção, a migration e o trigger protetor já existentes; se faltar um pré-requisito, explicitar o bloqueio.
- Excluir em lotes, respeitando a política de cinco anos e a proteção do banco.
- Testar que registros mais recentes, inclusive com 4 anos e 364 dias, são preservados.
- Testar explicitamente a tentativa de exclusão de um registro protegido e o bloqueio pelo trigger.
- Registrar contagem do expurgo em log e métrica, sem expor dados auditados.

**Evidência esperada:** testes das fronteiras temporais e do trigger, com contagem de itens removidos.

---

# RETENÇÃO-04 — Remover objetos órfãos do storage

**Issue:** [#77](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/77)  
**Status no Kanban fornecido:** Backlog

## Descrição original da issue

### Contexto e Objetivo
A foto é gravada no MinIO antes da transação do banco. Se a transação falha, o arquivo fica sem registro. Esta etapa remove esses arquivos depois de 24 horas.

**Requisitos:** RF-47, RN-109, RN-112  
**Depende de:** [retenção - 01], [evidência - 01], [evidência - 02]

### Critérios de Aceite (Definition of Done)
- [ ] Objeto com mais de 24 horas sem evidência correspondente, ou que não seja o original nem um derivado registrado, é removido.
- [ ] Objeto de 23 horas sem registro permanece.
- [ ] Objetos de evidências em `RECEBIDA` ou `PROCESSANDO` nunca são removidos.

### Passos Técnicos / Checklists
- [ ] Listar o bucket com paginação
- [ ] Extrair o id da evidência pela convenção de chave
- [ ] Criar o passo `LimpezaOrfaos`

## Orientações para implementação com IA

- Inspecionar a convenção de chaves vigente do MinIO e os contratos públicos do módulo de evidências; não presumir um formato sem conferir o código.
- Listar os objetos de maneira paginada e validar a idade e o vínculo ao registro da evidência.
- Distinguir o objeto original, derivados registrados e objetos não vinculados.
- Preservar evidências em `RECEBIDA` ou `PROCESSANDO` independentemente da idade.
- Testar órfãos com mais de 24 horas, objetos com 23 horas e objetos de evidências válidas ou em processamento.

**Evidência esperada:** testes com bucket descartável, mostrando preservação e exclusão corretas.

---

# RETENÇÃO-05 — Consultar evidência expurgada

**Issue:** [#78](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/78)

## Descrição original da issue

### Contexto e Objetivo
Mesmo depois de apagado o conteúdo, o gestor precisa provar que a evidência existiu, quando foi enviada e quando foi expurgada.

**Requisitos:** RF-46, RN-104
**Depende de:** [retenção - 02]

### Critérios de Aceite (Definition of Done)
- [ ] `GET /evidencias/{id}` (GESTOR) retorna os metadados também para evidência `EXPURGADA`: id, atendimento, chamado, envio, expurgo e hash.
- [ ] A listagem do chamado mostra a evidência expurgada sem miniatura, com o estado.
- [ ] Cliente e técnico não acessam os metadados de evidência expurgada.

### Passos Técnicos / Checklists
- [ ] Criar o endpoint de metadados no módulo `evidencia`
- [ ] Ajustar a listagem de [evidência - 04] para o estado `EXPURGADA`

## Orientações para implementação

- Publicar o endpoint sob o prefixo `/api` e restringi-lo ao gestor.
- Exibir evidências expurgadas na listagem do gestor com estado, sem miniatura, e proteger os metadados nas listagens de cliente e técnico.
- Preservar o `410 EVIDENCIA_EXPURGADA` no endpoint de conteúdo.

---

## Ordem recomendada de execução

1. **Inspecionar dependências:** plataforma-04, outbox-01, evidência-01, evidência-02, auditoria-01 e contratos do módulo chamado.
2. **RETENÇÃO-01:** orquestração diária, isolamento entre passos e limpeza da outbox.
3. **RETENÇÃO-03:** expurgo da auditoria depois de validar o trigger protetor.
4. **RETENÇÃO-02:** expurgo das evidências vencidas com preservação de metadados e auditoria.
5. **RETENÇÃO-04:** limpeza dos órfãos depois de confirmar convenção de chaves e estados de evidências.
6. **RETENÇÃO-05:** consultar metadados e validar o acesso por perfil.
7. **Integração final:** validar os quatro passos da rotina e a consulta com dados sintéticos.

> Ordem sugerida, sujeita às dependências efetivamente implementadas. Não declarar uma task concluída apenas porque o código compila.

## Checklist de validação final

- [ ] Dependências e contratos das cinco issues verificados.
- [ ] Agendamento diário configurável, sem endpoint manual.
- [ ] Falha de um passo não bloqueia os demais.
- [ ] Outbox: eventos publicados após sete dias são elegíveis; pendentes permanecem.
- [ ] Evidências: expurgo após 180 dias de estado final elegível, com metadados e auditoria preservados.
- [ ] Auditoria: registros protegidos pelo limite de cinco anos não podem ser removidos.
- [ ] Storage: objetos órfãos respeitam o limite de 24 horas e estados protegidos.
- [ ] Consulta: gestor acessa os metadados de expurgadas. Cliente e técnico não os recebem.
- [ ] Testes executados em infraestrutura isolada com dados sintéticos.
- [ ] Logs, métricas, testes e bloqueios registrados por issue.
- [ ] Nenhuma alteração no GitHub realizada sem autorização.

## Fontes oficiais

- [RETENÇÃO-01 — Issue #74](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/74)
- [RETENÇÃO-02 — Issue #75](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/75)
- [RETENÇÃO-03 — Issue #76](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/76)
- [RETENÇÃO-04 — Issue #77](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/77)
- [RETENÇÃO-05 — Issue #78](https://github.com/gestao-SLA-prestadoras-de-servico/backend/issues/78)
