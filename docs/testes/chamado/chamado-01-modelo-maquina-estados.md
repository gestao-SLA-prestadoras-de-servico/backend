# Testes — [chamado - 01] Criar o modelo do chamado e a máquina de estados

> Executados em 14/11/2026, na branch `feat/chamados` (alterações ainda não commitadas), com Testcontainers (`pgvector/pgvector:pg17`). As quebras propositais e a verificação no dev foram feitas numa cópia do projeto, fora do repositório.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 330 testes, 0 falhas (`EstadoChamadoTest` 40, `AtendimentoTest` 5, `ChamadoModeloTest` 7, `AtendimentoChamadoTest` 5 e os 273 anteriores; `RelogiosSlaTest` e `ApuracaoSlaTest` ajustados para criar o chamado antes dos relógios) | ✅ |
| 2 | 36 combinações origem × destino | `EstadoChamadoTest.shouldAllowOnlyTransitionsOfRn10` (parametrizado) | só as 9 da RN-10 passam; as 27 outras lançam `TRANSICAO_INVALIDA` sem mudar o estado | passou | ✅ |
| 3 | Estados finais | `shouldRejectEveryTransitionFromFinalStates` | ENCERRADO e CANCELADO sem destino | passou | ✅ |
| 4 | Datas de resolução e de estado final | `shouldRecordResolutionAndFinalStateInstants` | `resolvido_em` em RESOLVIDO; `estado_final_em` em ENCERRADO e CANCELADO | passou | ✅ |
| 5 | Prioridade efetiva | `shouldUseManagerPriorityOrPolicyDefault` | sem a do gestor, a padrão (a sugerida ALTA é ignorada); com ela, a do gestor | passou | ✅ |
| 6 | Número pela sequência | `ChamadoModeloTest.shouldNumberChamadosFromSequence` | crescente | passou | ✅ |
| 7 | Auditoria e histórico em toda transição | `shouldAuditAndRecordHistoryOnEveryStateChange` (ABERTO → ATRIBUIDO → CANCELADO) | 2 auditorias `MUDANCA_ESTADO`/`CHAMADO` com `de`/`para` e ator; 2 itens de histórico visíveis aos três perfis | passou | ✅ |
| 8 | Transição inválida não grava | `shouldRejectInvalidTransitionWithoutWritingAnything` | `TRANSICAO_INVALIDA`; estado, auditoria e histórico intactos | passou | ✅ |
| 9 | Bloqueio otimista (RN-117) | `shouldFailWithConflictWhenChamadoChangedConcurrently` (outra transação muda o chamado entre a leitura e a gravação) | `OptimisticLockingFailureException`; vale a outra gravação; sem histórico | passou | ✅ |
| 10 | Aviso de violação | `shouldWriteViolationNoticeToManagersAndTechnician` | `VIOLACAO` no canal `interna`, com número, título, relógio (`CAMPO` → `ATENDIMENTO_CAMPO`), gestor e técnico | passou | ✅ |
| 11 | `eventoId` determinístico (RN-54) | `shouldNotDuplicateSameViolationButNotifyNewCycle` | a mesma violação duas vezes gera 1 evento; o ciclo 2 gera outro | passou | ✅ |
| 12 | Sem técnico | `shouldNotifyOnlyManagersWhenNoTechnicianAssigned` | só gestores | passou | ✅ |
| 13 | Quebra: ABERTO → EM_ATENDIMENTO aceito | cópia, `-Dtest=EstadoChamadoTest` | falha | 1 combinação falhou | ✅ |
| 14 | Quebra: ENCERRADO → ATRIBUIDO aceito | idem | falha | 2 testes falharam | ✅ |
| 15 | Quebra: transição sem auditoria | cópia, `-Dtest=ChamadoModeloTest` | falha | teste 7 falhou | ✅ |
| 16 | Quebra: `eventoId` aleatório | idem | falha | testes 10, 11 e 12 falharam | ✅ |
| 17 | Quebra: aviso sem o técnico | idem | falha | teste 10 falhou | ✅ |
| 18 | Dev: migração V12 | stack de dev completa a partir da cópia (`-p flowsla-verificacao`, volumes próprios); `flyway_schema_history` e `psql` no banco `app_principal` | V12 aplicada; `numero` com `nextval('chamado_numero_seq')`; FKs `relogio_sla_chamado_fk` e `apuracao_sla_chamado_fk`; triggers do histórico | V12 com sucesso e todos os objetos presentes; `Started BackendApplication` com `ddl-auto=validate` | ✅ |

## Observações

1. **Observação de ambiente:** na cópia, `infra/rabbitmq` foi reetiquetada com `chcon -t container_file_t` (mesmo achado da outbox-03). O ambiente de verificação foi derrubado com `down -v` só no projeto `flowsla-verificacao`; os volumes `infra_*` de dev não foram tocados.
2. **Achado:** a task pede para criar a porta `ChamadosDoTecnico`, mas ela já existia em `identidade.api` desde a autenticação-06. Nada foi criado; a implementação segue para a [chamado - 13].
3. **Achado:** a FK nova de `relogio_sla` e `apuracao_sla` quebraria os testes do sla, que usavam `chamado_id` aleatório. Eles passaram a criar o chamado por SQL (`ChamadoTeste`).
4. **Não verificado aqui:** o número em aberturas concorrentes. A sequência do Postgres garante a unicidade, e o teste com aberturas simultâneas está na [chamado - 03], que cria a abertura.
5. **Não verificado:** o 422 por HTTP. Ainda não há endpoint de transição; a conversão de `RegraNegocioException` em 422 já é coberta pelo `GlobalExceptionHandlerTest`.
