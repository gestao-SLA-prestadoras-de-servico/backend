# [chamado - 01] Criar o modelo do chamado e a máquina de estados

> Requisitos: RN-09 a RN-16, RN-117, RN-125 · Depende de: [plataforma - 03], [plataforma - 05], [outbox - 01], [outbox - 04], [auditoria - 01], [autenticação - 05]

## Contexto

O chamado é o centro do sistema. As transições de estado são garantidas no domínio, não no controller, para que nenhuma tela ou rotina leve o chamado a um estado inválido. Validação, reabertura, rotinas automáticas e ações do gestor podem acontecer ao mesmo tempo, então toda mutação usa bloqueio otimista.

Esta task também cria duas peças usadas por várias outras:

- o histórico do chamado: a linha do tempo visível aos três perfis, separada da auditoria, que é só do gestor;
- o `NotificadorViolacao`: o único lugar que grava eventos de violação na outbox.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| Teste parametrizado cobre as 36 combinações origem × destino: só as transições da RN-10 passam | `EstadoChamadoTest.shouldAllowOnlyTransitionsOfRn10` percorre os 6 × 6 pares. Só as 9 transições da RN-10 passam (6 de fluxo e 3 para CANCELADO); as outras 27 lançam `TRANSICAO_INVALIDA` e não mudam o estado. |
| ENCERRADO e CANCELADO não aceitam nenhuma transição | `EstadoChamado.destinos()` é vazio nos dois (RN-11). Coberto pelas 12 combinações a partir deles e por `shouldRejectEveryTransitionFromFinalStates`. |
| Transição inválida retorna 422 `TRANSICAO_INVALIDA` | `Chamado.transicionar` lança `RegraNegocioException(TRANSICAO_INVALIDA)`, que o `GlobalExceptionHandler` já devolve como 422. Ainda não há endpoint de transição; os endpoints das próximas tasks herdam o comportamento. |
| O número legível (#1042) é único e crescente mesmo com aberturas concorrentes | Coluna `numero` com `DEFAULT nextval('chamado_numero_seq')` (sequência da V1) e UNIQUE. A sequência do Postgres não repete número entre transações concorrentes. O teste concorrente fica na [chamado - 03], que cria a abertura. |
| A prioridade efetiva é a do gestor ou, se não definida, a padrão da versão da política | `Chamado.prioridadeEfetiva()`. A `prioridade_padrao` da versão é copiada na abertura, porque a versão é imutável. A sugerida pelo cliente não entra na conta (RN-15). |
| Toda mudança de estado grava auditoria e item de histórico | `TransicaoChamado.transicionar` é o único caminho: a entidade valida, e a auditoria `MUDANCA_ESTADO` `{de, para}` e o histórico são gravados na mesma transação. |
| Testes unitários criados e passando | `EstadoChamadoTest` (40), mais `ChamadoModeloTest` (integração, 7). |

## Passos técnicos realizados

- [x] **Migração da tabela `chamado`** (`V12__cria_chamado.sql`). Colunas:
  - `numero`, `contrato_id`, `cliente_id`, `politica_versao_id` e `prioridade_padrao`;
  - `titulo`, `descricao` e `local`;
  - `prioridade_sugerida`, `prioridade_definida` e `estado`;
  - `tecnico_sub`, `posicionamento` e `posicionado_em`;
  - `aberto_em`, `resolvido_em`, `aviso_enviado_em`, `estado_final_em` e `motivo_cancelamento`;
  - `version`.

  FKs para contrato, cliente e versão da política; CHECKs de estado e de prioridade; índices em cliente, técnico e estado.
- [x] **Enum `EstadoChamado` com o mapa de transições**, em `chamado.api`: `destinos()`, `podeIrPara()` e `finalizado()`.
- [x] **`transicionar(destino)` na entidade**: `Chamado.transicionar(destino, instante)`. Preenche `resolvido_em` ao entrar em RESOLVIDO e `estado_final_em` ao entrar em ENCERRADO ou CANCELADO, que é o início dos 180 dias de retenção (RN-100).
- [x] **Migração e serviço de `chamado_historico`**. Tabela com evento, ator (sub e papel), `ocorrido_em` (do `Clock`), `visivel_cliente`, `visivel_tecnico` e `dados`. Imutável por trigger. Serviço `HistoricoChamado.registrar`. A visibilidade vem de `TipoEventoHistorico`, e o gestor vê tudo.
- [x] **`NotificadorViolacao`**:
  - recebe as `Violacao` devolvidas pelo sla;
  - monta os destinatários (gestores ativos e técnico atribuído) com o `MontadorDestinatarios`;
  - grava `VIOLACAO` na outbox com `eventoId` = UUID derivado de chamado + relógio + marco inicial (RN-54).
- [x] **Porta `ChamadosDoTecnico` para o módulo identidade**: já existia em `identidade.api`, criada na autenticação-06 e usada como `Optional`. A implementação continua na [chamado - 13], como a task prevê.
- [x] **FK dos relógios e da apuração**: a V12 acrescenta `relogio_sla.chamado_id → chamado` e `apuracao_sla.chamado_id → chamado`, pendência das sla-02 e sla-05.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Onde fica o `EstadoChamado` | `chamado.api` | A `ChamadosDoTecnico` e o evidencia precisam conhecer os estados. |
| Prioridade efetiva sem reler a política | O chamado guarda uma cópia da `prioridade_padrao` da versão | A versão é imutável, então a cópia nunca fica desatualizada. Assim a listagem filtra por prioridade efetiva no banco (`coalesce`). |
| Hora da abertura | `aberto_em`, vinda do `Clock` | O `created_at` vem do relógio da JVM e não pode ser o marco do SLA (`docs/convencoes-banco.md`). |
| Visibilidade do histórico | Abertura e mudança de estado: os três perfis. Alteração de prioridade: só o gestor | Decisão do usuário. |
| Evento do histórico sem CHECK | Texto livre, como a `operacao` da auditoria | Cada task acrescenta os seus eventos sem migração. |
| Histórico imutável | Trigger rejeita `UPDATE`, `DELETE` e `TRUNCATE`; entidade `@Immutable` | Convenção de tabela imutável do projeto (`docs/convencoes-banco.md`). |
| FK do sla para chamado | Criada agora | Decisão do usuário. `RelogiosSlaTest` e `ApuracaoSlaTest` passam a criar o chamado por SQL (`ChamadoTeste`). |
| `eventoId` da violação | `UUID.nameUUIDFromBytes("violacao:" + chamado + ":" + tipo + ":" + marcoInicial)` | Determinístico (RN-54). A outbox ignora `eventoId` repetido, então a mesma violação nunca gera dois avisos, e o ciclo novo, com outro marco, gera um aviso novo. |
| Escrita na transação do chamador | `TransicaoChamado`, `HistoricoChamado` e `NotificadorViolacao` com `propagation = MANDATORY` | Mesmo padrão da auditoria e da outbox: estado, histórico, auditoria e aviso commitam juntos. |
| Concorrência | `@Version` no chamado | RN-117. Conflito sai como 409 `CONFLITO_VERSAO` pelo handler existente. |

## Atenção ao atualizar um ambiente existente

- A `V12` roda sozinha. Nenhuma variável nova.
- Os relógios e as apurações já gravados precisam apontar para chamados existentes, por causa das FKs novas. Até aqui nada grava relógio fora dos testes, então um ambiente real não tem esses registros.
- Novas mudanças de estado devem passar por `TransicaoChamado`, nunca por `Chamado.transicionar` direto.

## Como verificar

Ver `docs/testes/chamado/chamado-01-modelo-maquina-estados.md`.
