# [chamado - 13] Transferir chamados na inativação de técnico

> Requisitos: RN-121, RN-133, RN-134, RN-135 · Depende de: [chamado - 01], [chamado - 02], [autenticação - 06]

## Contexto

Quando um técnico é inativado, todos os chamados não finalizados dele passam ao substituto na mesma transação da inativação. Assim nenhum chamado fica com técnico inativo, e uma futura reabertura chega a quem pode atendê-la.

**A maior parte já existia.** A [chamado - 07] implementou a porta `ChamadosDoTecnico` inteira (`TransferenciaChamados`), por decisão do usuário, para a carga de `GET /tecnicos`. Esta task acrescentou o endpoint de pré-visualização e o teste ponta a ponta pela inativação.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| ATRIBUIDO troca o técnico; EM_ATENDIMENTO volta para ATRIBUIDO com o substituto e interrompe o atendimento; RESOLVIDO mantém o estado e troca o técnico | `TransferenciaChamados.transferir`, que reaproveita `AtribuicaoService.trocarTecnico` (chamado-07). |
| Nenhum relógio é alterado | A troca de técnico não chama o sla (RN-22, RN-135). O teste compara as linhas de `relogio_sla` antes e depois da inativação. |
| O substituto recebe ATRIBUICAO por chamado; o inativado não recebe REMOCAO | `trocarTecnico` grava a `ATRIBUICAO`; a REMOCAO só existe na reatribuição pelo gestor. |
| Inativar técnico com 3 chamados (um em cada estado) transfere os 3 em uma transação | `InativacaoUsuarioService` grava a inativação e chama `transferir` (`MANDATORY`) na mesma transação. O teste inativa pela API. |
| `GET /usuarios/{id}/chamados-a-transferir` mostra a contagem por estado para o modal do gestor | `GET /api/usuarios/{id}/chamados-a-transferir` (GESTOR) responde `{"ATRIBUIDO": n, "EM_ATENDIMENTO": n, "RESOLVIDO": n}`. Usuário inexistente dá 404. |
| Testes de integração criados e passando | `TransferenciaTecnicoTest` (4: 2 novos) e a rota no `AcessoPorRotaTest`. |

## Passos técnicos realizados

- [x] **Implementar a porta `ChamadosDoTecnico`**: já implementada na chamado-07. Nesta task, ganhou `contarNaoFinalizadosPorEstado`.
- [x] **`TransferenciaService` participando da transação da inativação**: é a `TransferenciaChamados`, da chamado-07, com `Propagation.MANDATORY`.
- [x] **Auditoria de transferência por chamado**: `TRANSFERENCIA_POR_INATIVACAO {anterior, novo}`, da chamado-07.
- [x] **Endpoint de pré-visualização**, no `UsuarioController` e no `InativacaoUsuarioService`, com a regra GESTOR na `SegurancaConfig` e o `@PreAuthorize`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Onde fica o endpoint | No identidade (`/api/usuarios/...`), pela porta `ChamadosDoTecnico` | A rota é de usuário e recebe o `id` do usuário, não o `sub`. O identidade já declara a porta e a usa na inativação. |
| Formato | Mapa com as três chaves sempre presentes, em texto | O identidade não pode importar o estado do chamado (o chamado depende do identidade). Com as três chaves fixas, o frontend não trata ausência. |
| Usuário que não é técnico | Zeros | Não tem chamados atribuídos; o modal não precisa de outro caminho. |
| Sem o módulo chamado (só em teste) | Mapa vazio | Mesmo tratamento do `Optional` da autenticação-06. |
| Contagem | Uma consulta por estado (3) | Só o modal usa. Fica marcado com `ponytail:` no código. |

## Atenção ao atualizar um ambiente existente

- Nenhuma migração nem variável nova.

## Como verificar

Ver `docs/testes/chamado/chamado-13-transferir-chamados-inativacao.md` e o passo 7K.12 do `docs/plano-testes-manuais.md`.
