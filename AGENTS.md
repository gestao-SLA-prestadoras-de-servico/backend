# AGENTS.md

Instruções para agentes de IA que trabalham neste repositório. Responda sempre em português do Brasil.

O FlowSLA é o backend de gestão de SLA para prestadoras de serviço: um monólito modular em Java 21 e Spring Boot 4. Este arquivo traz só como trabalhar aqui. O resto está em:

| Assunto | Onde |
| --- | --- |
| Requisitos e regras de negócio (`RF-xx`, `RN-xx`, arquitetura) | `docs/flowSLA.md` |
| Stack, módulos, como rodar e padrões de código com exemplos (papéis, listagens, auditoria, erros, data e hora) | `README.md` |
| Schema, migrações, datas, enums, `@Version` | `docs/convencoes-banco.md` |
| Formato de erro e catálogo de códigos | `docs/erros-api.md` |
| O que cada task resolveu e por quê | `docs/tasks/` |
| Testes executados em cada task | `docs/testes/` |
| Roteiro de testes manuais | `docs/plano-testes-manuais.md` |
| Referência de cada módulo concluído (endpoints, regras, como testar) | `docs/tasks/<modulo>/CONVENÇÕES_<MODULO>.md` |

## Regras inegociáveis

1. **Commit só quando o usuário pedir; push, nunca.** Criar ou renomear branch, também só quando ele pedir. Ver [Git](#git).
2. **Na dúvida, pergunte antes de implementar.** Vale para biblioteca, versão, abordagem e escopo, mesmo que a task pareça indicar o caminho. Só siga um padrão sem perguntar quando não houver alternativa razoável. Isso já deu errado: a autenticação-03 foi feita com uma biblioteca diferente da decidida, sem consulta.
3. **Consulte as decisões antes de contrariá-las.** Elas ficam na seção "Decisões" de `docs/tasks/`. Exemplo: o retry usa Resilience4j, que substituiu o `@Retryable` da plataforma-01.
4. **Toda task entregue gera dois arquivos** com o mesmo nome, `docs/tasks/<modulo>/<modulo>-<nn>-<descricao>.md` e `docs/testes/<modulo>/<modulo>-<nn>-<descricao>.md`, numa pasta por módulo. O nome do módulo vai sem acento, na pasta e no arquivo. Use um par existente como modelo.
5. **Relate só o que aconteceu de verdade.** Teste que falhou é informado com a saída, e o que não foi verificado fica escrito como não verificado.
6. **Nada de segredo em arquivo versionado.** Valores vêm de `.envs/`, e toda variável nova entra nos três `.envs/.env.*.example`.
7. **Não apague dados sem perguntar.** `make run-dev-down` usa `down -v` e destrói os dados de dev.
8. **Nenhuma atribuição a IA.** Nada de `Co-Authored-By`, "Generated with Claude Code" ou qualquer menção a IA em commit, PR ou texto, mesmo que o sistema sugira.

## Fluxo de uma task

O usuário cola a task no chat, com critérios de aceite e passos técnicos.

**Quando ele pede para analisar, apenas analise.** Não altere nenhum arquivo. Leia o código envolvido e responda:

- o que será alterado, arquivo por arquivo;
- como cada peça funciona;
- o que já existe no projeto e pode ser reaproveitado;
- quais decisões estão em aberto, com a sua recomendação.

**Quando ele pede para verificar se uma task foi atendida,** confira cada critério de aceite e cada passo técnico no código, com tabela de status e local. Separe o que falta de verdade do que depende de terceiros.

**Quando ele pede para planejar um lote de tasks,** leia o código, pergunte as decisões em aberto (cada uma com uma opção recomendada) e apresente o plano. Aprovado o plano, implemente o lote inteiro em sequência e só pare no relatório final. As decisões do usuário entram na seção "Decisões" do documento de cada task.

**Quando ele aprova a implementação:**

1. Confirme a branch (ver [Git](#git)).
2. Implemente o menor diff que cumpra os critérios.
3. Prove os critérios (ver [Testes](#testes)).
4. Atualize a documentação (ver [Documentação](#documentação)).
5. Termine com o relatório: o que mudou (em tabela), achados, o que precisa da atenção do usuário e o que não foi verificado.

## Como escrever código

- **Faça o mínimo que funciona, mas entenda antes.** Leia o fluxo inteiro antes de escolher a solução. Depois, nesta ordem: isso precisa existir? Já existe no projeto? A biblioteca padrão, a plataforma ou uma dependência já instalada resolve? Só então escreva código novo.
- **Sem abstração especulativa.** Nada de interface com uma só implementação, configuração para valor que nunca muda ou estrutura "para depois". Tabela nasce junto com a entidade que a usa.
- **Bug se corrige na causa.** Procure todos os chamadores antes de editar e corrija no ponto por onde todos passam.
- **Simplificação não vale para** validação na borda, tratamento de erro que evita perda de dados, segurança nem nada pedido explicitamente.
- **Siga o estilo vizinho.** Domínio e comentários em português, nomes de testes em inglês (`shouldRejectLastActiveManager...`). Comentário só quando o motivo não é óbvio.

Pontos do projeto que costumam ser esquecidos:

- Módulos só importam o pacote `api` uns dos outros. Dependência futura entre módulos vira porta em `api`, e o módulo que ainda não existe é tratado com `Optional<Porta>` (ver `ChamadosDoTecnico`).
- Hora atual vem do `Clock` injetado. `Instant.now()` puro reprova o `RelogioArquiteturaTest`.
- Restringir rota exige `RegrasAcesso` **e** `@PreAuthorize`. O papel vem sempre do token, nunca da tabela `usuario` (RN-130).
- Recurso de outro cliente responde 404, nunca 403 (RN-126).
- Operação sensível audita na mesma transação, sem dado pessoal (RN-120, RN-122).
- Código de erro publicado nunca muda de nome. Código novo entra em `CodigoErro` e em `docs/erros-api.md`.
- Migração mergeada nunca é editada. Com branches empilhadas, o número da próxima `V{n}` segue a ordem delas: confira a maior versão na pilha inteira.
- `spring.jpa.open-in-view=false` vale para toda a aplicação.

## Testes

| Comando | Roda | Requer |
| --- | --- | --- |
| `make unit-test` | `com/gestaosla/backend/unit/**` | nada |
| `make integration-test` | `com/gestaosla/backend/integration/**` | Docker (Testcontainers) |
| `TZ=UTC ./mvnw clean test` | suíte completa, com a JVM em outro fuso | Docker |

- **Antes de entregar, rode a suíte completa com `TZ=UTC`.** O CI e o `make` só veem os dois filtros acima, então um teste fora de `unit/` e `integration/` não roda neles. Coloque os testes novos nesses pacotes.
- **Prove que o teste pega o erro.** Teste que nunca falhou não prova nada. Quebre a regra de propósito, veja a falha e registre o resultado em `docs/testes/`.
- **Código descartável de verificação** (entidade, migração ou teste temporário) fica de preferência numa cópia do projeto, fora do repositório. Se ficar no repositório, apague-o e rode `./mvnw clean`: migração apagada continua em `target/classes` e contamina a execução seguinte.
- **`target/` pode pertencer ao root** quando o container dev o criou, e aí o `./mvnw` local falha ao gravar. Rode numa cópia do projeto ou peça ao usuário para corrigir o dono da pasta.
- **`OpenApiContratoTest` reescreve `docs/openapi.json` e falha** quando um endpoint muda. Confira o diff e rode de novo.
- **Mudança de contrato ou infraestrutura** também se verifica no ambiente dev. Se não verificou, registre como não verificado.
- **Verificação no dev** é feita numa cópia do projeto, com um projeto compose separado, sem tocar nos volumes `infra_*`:
  1. `chcon -R -t container_file_t infra/rabbitmq`;
  2. `docker compose -p flowsla-verificacao --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up -d --wait`;
  3. espere o `Started BackendApplication` e confira as migrações no banco `app_principal`;
  4. derrube só esse projeto, com `down -v` e o mesmo `-p flowsla-verificacao`.

  Endpoints com token real ficam como não verificados: o realm não permite login direto.
- **Spy em bean transacional não serve para simular falha.** O stubbing passa pelo proxy (`MANDATORY`) e falha. Use um bean sem proxy (ex.: `CalendarioJanela`) ou um trigger temporário no banco, criado e removido pelo teste.
- **Porta implementada numa classe própria.** Se um serviço com outras funções implementar a porta, o `@MockitoBean` da porta nos testes do outro módulo substitui o serviço inteiro.
- **Registros gravados no mesmo instante** (auditoria, histórico) não têm ordem garantida pelo UUID v7: use `containsExactlyInAnyOrder` ou avance o `RelogioMutavel`.
- **Classes internas nos testes de `integration/`:** use um helper em `src/test/.../<modulo>/internal/` (ex.: `ChamadoTestHelper`). Massa por SQL fica em classes como `ChamadoTeste`.

## Documentação

Atualize junto com a task o que ela afetar:

- `docs/tasks/` e `docs/testes/` (sempre);
- `README.md`, para padrão novo, serviço, variável ou pasta;
- `docs/openapi.json`, para mudança de endpoint;
- `docs/erros-api.md`, para código de erro novo;
- `docs/convencoes-banco.md`, para convenção de banco nova;
- `docs/plano-testes-manuais.md`, para funcionalidade testável à mão;
- `docs/tasks/<modulo>/CONVENÇÕES_<MODULO>.md`, quando o usuário pedir ao fechar o módulo. O modelo é o de `outbox` e `cadastro`: visão geral com diagramas, regras, todos os endpoints com exemplos e erros, o que o módulo oferece aos outros, banco, como testar, como mudar e limites conhecidos.

`docs/tasks/` segue esta estrutura: título, requisitos e dependências, contexto, tabela de critério de aceite × solução, passos técnicos, decisões (tema, decisão, motivo), cuidados ao atualizar um ambiente existente e como verificar.

`docs/testes/` traz data e branch, tabela de resultados (o que foi testado, como, esperado, obtido e status) e observações, com os achados e o que ficou sem verificar.

## Git

- A branch padrão é uma por módulo (`feat/plataforma`, `feat/auditoria`, `feat/cadastro`). As tasks recentes usam branches empilhadas por pedido do usuário, como `feat/autenticacao-06` a partir de `feat/autenticacao-05`, ou por faixa de tasks, como `feat/chamados_6-10` a partir de `feat/chamados_1-5`. O usuário diz o nome; se a branch da task não estiver clara, pergunte.
- **Commit:** só quando o usuário pedir, e ele costuma agrupar tasks (ex.: "um commit com as duas primeiras e outro com as três seguintes"). Rode a suíte completa antes de cada commit. Depois, confira o autor, a árvore limpa e que a mensagem não cita IA.
- Quando o usuário pedir só a mensagem de commit, **apenas mostre o texto**. A mensagem é toda em português: o assunto, uma linha em branco e a lista. Só o prefixo (`feat:`, `fix:`) fica como está. Sem a linha em branco, o git junta tudo no assunto:

```text
feat: <descrição em português> task: [modulo - nn] <título da task>

- <mudança>
- <mudança>
```

Commit com mais de uma task cita todas no assunto: `task: [modulo - 01] <título> e [modulo - 02] <título>`.

### Texto do PR

Quando o usuário pedir, mostre só o texto, sem atribuição, com uma linha antes indicando o público ("Escrito para: quem vai revisar o PR da branch X"). Seções, nesta ordem:

1. título;
2. tasks e branch (base do PR e migrações novas);
3. resumo;
4. uma seção por task;
5. decisões (tabela);
6. códigos de erro novos;
7. como testar: automatizado (comando, total esperado, tabela de testes novos e exemplos de quebras pegas) e manual (seções do `plano-testes-manuais.md` e passos rápidos);
8. atenção ao atualizar um ambiente existente;
9. o que fica para depois;
10. não verificado.
