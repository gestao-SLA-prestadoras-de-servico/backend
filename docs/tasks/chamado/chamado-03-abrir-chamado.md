# [chamado - 03] Abrir chamado

> Requisitos: RF-12, RF-30, RN-01, RN-03, RN-05, RN-15, RN-16, RN-61, RN-111 · Depende de: [chamado - 01], [cadastro - 04], [sla - 02]

## Contexto

O cliente abre o chamado sob um contrato vigente da própria empresa. Na mesma transação, os três prazos de SLA são calculados e gravados, junto com a auditoria; se qualquer parte falhar, nada é gravado. A prioridade sugerida pelo cliente fica registrada, mas não muda nenhum prazo.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `POST /chamados` (CLIENTE) cria o chamado com número legível e os três prazos gravados | `POST /api/chamados` responde 201 com `Location` e o chamado na visão do cliente: número, os três relógios com `venceEm` e os atendimentos. Os prazos ficam em `relogio_sla`, via `RelogiosSla.iniciarRelogios`. |
| Contrato de outra empresa retorna 404 | `ContratoConsulta.politicaParaAbertura(contratoId, empresaClienteId)` responde como contrato inexistente (RN-126). A empresa vem do usuário autenticado, nunca do corpo. |
| Contrato fora da vigência retorna 422 `CONTRATO_FORA_VIGENCIA` | Mesma consulta, com `Contrato.estaVigenteEm(LocalDate.now(clock))`, que inclui o primeiro e o último dia (RN-01). |
| Contrato sem política retorna 422 `CONTRATO_SEM_POLITICA` | A consulta termina em `versaoVigenteDaPolitica`, que já lança o erro (RN-03). |
| Prioridade sugerida "alta" não altera os prazos | Os prazos usam a prioridade efetiva: sem a do gestor, vale a padrão da versão (RN-15, RN-16). A sugerida fica em `prioridade_sugerida`, só como informação. |
| Chamado aberto fora da janela começa a contar na próxima abertura | O `CalendarioJanela` normaliza o início (RN-61). O `aberto_em` guarda a hora real; o `marco_inicial` do relógio também, e só o vencimento conta a partir das 08:00 do próximo dia útil. |
| Falha simulada no cálculo dos prazos desfaz tudo | `AberturaChamadoService.abrir` é uma transação só (RN-111). Com o `CalendarioJanela` lançando exceção, nada fica gravado: nem chamado, nem auditoria, nem histórico. |
| Testes de integração criados e passando | `AberturaChamadoTest` (10), mais as rotas no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **DTO de abertura com validação**: `DadosAbertura`.
  - `contratoId` é obrigatório.
  - `titulo` (até 200), `descricao` (até 4000) e `local` (até 300) não podem ficar em branco.
  - `prioridadeSugerida` é opcional.
- [x] **`AberturaChamadoService` em uma transação**, nesta ordem:
  1. valida o contrato;
  2. grava o chamado com a versão da política (`saveAndFlush`, para obter o número);
  3. chama `sla.iniciarRelogios`;
  4. grava a auditoria `ABERTURA_CHAMADO` com `{numero, contratoId}`;
  5. grava o histórico `ABERTURA`.
- [x] **`POST /chamados`**: `POST /api/chamados`, com `RegrasAcesso` e `@PreAuthorize` só para CLIENTE.
- [x] **Endpoint documentado no OpenAPI** (`docs/openapi.json` regenerado).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Validação do contrato | Método novo `ContratoConsulta.politicaParaAbertura(contratoId, empresaClienteId)` | Fecha a pendência "vigência sem consulta pública" da cadastro-04. As três regras (empresa, vigência e política) ficam no dono do contrato, numa chamada só. |
| Ordem das checagens | Empresa (404), depois vigência (422) e por fim política (422) | Para um contrato de outra empresa, nada é revelado além de "não existe". |
| Prioridade sugerida | Opcional | RF-12 cita a sugestão, mas ela não tem efeito; o cliente pode não ter opinião. |
| Falha simulada no teste | `@MockitoSpyBean` no `CalendarioJanela` | Ele é o cálculo dos prazos. O spy no `RelogiosSla` esbarra no proxy transacional: o stubbing passa pelo `MANDATORY` e falha. |
| Resposta do POST | O detalhe na visão do cliente, o mesmo do `GET /api/chamados/{id}` | O frontend mostra o chamado aberto, com os prazos, sem uma segunda chamada. |
| Auditoria sem dado pessoal | `{numero, contratoId}`; título e descrição ficam fora | RN-122: o texto livre do cliente pode ter dado pessoal. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova nesta task.
- Para abrir chamado, o usuário CLIENTE precisa de um contrato vigente da empresa com política vinculada (cadastro-02 e cadastro-04).

## Como verificar

Ver `docs/testes/chamado/chamado-03-abrir-chamado.md` e a seção 7D do `docs/plano-testes-manuais.md`.
