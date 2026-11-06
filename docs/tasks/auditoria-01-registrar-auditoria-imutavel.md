# [auditoria - 01] Registrar auditoria imutável

> Requisitos: RF-41, RN-107, RN-120, RN-121, RN-122, RN-124 · Depende de: [plataforma - 03]

## Contexto

A trilha de auditoria é o histórico defensável do sistema: quem fez o quê, quando e em qual entidade. O registro precisa entrar na mesma transação da operação e não pode ser alterado nem apagado por ninguém durante os 5 anos de retenção. A imutabilidade é garantida também no banco, não só no código. É pré-requisito da [autenticação - 04], que audita o cadastro de usuário.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| `AuditoriaService.registrar(...)` falha se chamado fora de uma transação | `@Transactional(propagation = MANDATORY)` no `AuditoriaServiceImpl`: sem transação ativa, `IllegalTransactionStateException`. |
| Rollback da operação de negócio não deixa registro de auditoria | Com `MANDATORY` o registro entra na transação de quem chama (RN-120) e some no rollback. |
| UPDATE direto na tabela, via SQL, falha | Trigger `auditoria_imutavel_linha` (`BEFORE UPDATE`) lança exceção; além disso, `REVOKE UPDATE` do usuário da aplicação. A entidade é `@Immutable` no Hibernate. |
| DELETE de registro com menos de 5 anos falha; com mais de 5 anos passa | O mesmo trigger, em `BEFORE DELETE`, rejeita `ocorrido_em > now() - 5 anos`. O registro vencido pode ser removido (RN-107), pela futura Rotina de Retenção. |
| O enum de operações cobre todos os itens da RN-121 | `OperacaoAuditada` tem os 15 itens da RN-121, mais `ABERTURA_CHAMADO` e `PRIMEIRO_POSICIONAMENTO` (decisão pendente da task, ver Decisões). `OperacaoAuditadaTest` confere item a item. |
| Testes de integração criados e passando | `AuditoriaTest` (8 casos), contra Postgres real (Testcontainers). |

## Passos técnicos realizados

- [x] **Migração da tabela `auditoria`** (`V4__cria_auditoria.sql`): `id`, `ocorrido_em`, `ator_tipo` (`USUARIO`/`SISTEMA`), `ator_sub`, `perfil`, `operacao`, `entidade_tipo`, `entidade_id`, `chamado_id`, `dados jsonb`. O CHECK `auditoria_ator_ck` exige `ator_sub` e `perfil` para usuário, e nenhum dos dois para o sistema.
- [x] **Índices** em `ocorrido_em`, `ator_sub`, `(entidade_tipo, entidade_id)` e `chamado_id`, os filtros da RF-40.
- [x] **Trigger que rejeita UPDATE e DELETE de registro com menos de 5 anos**: função `auditoria_imutavel()`. Também há trigger de comando contra `TRUNCATE`, que não dispara trigger de linha e apagaria tudo.
- [x] **Revogar UPDATE para o usuário da aplicação**: `REVOKE UPDATE, TRUNCATE ON auditoria FROM CURRENT_USER`, o usuário com que o Flyway roda (`app_principal`).
- [x] **Enum `OperacaoAuditada`** em `auditoria.api`; junto, `EntidadeAuditada` (CHAMADO, USUARIO, POLITICA_SLA, EVIDENCIA, PREFERENCIA_NOTIFICACAO).
- [x] **`AuditoriaService` com `@Transactional(propagation = MANDATORY)`**: interface em `auditoria.api`, implementação em `internal`. `ocorrido_em` vem do `Clock` injetado (RN-62). O repositório só tem `save`, sem alteração nem remoção.
- [x] **`Ator` e `Papel` em `shared.api`**: `Ator.usuario(sub, papel)` ou `Ator.SISTEMA`. O `Papel` saiu de `identidade.api` (ver Decisões).

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Abertura de chamado e primeiro posicionamento | Auditados (`ABERTURA_CHAMADO`, `PRIMEIRO_POSICIONAMENTO`) | Sugestão da própria task. A RN-111 já cita o "registro de auditoria" na abertura. |
| Quem informa o ator | Quem chama passa um `Ator` | Se o `auditoria` lesse o `UsuarioCorrente` do `identidade`, e o `identidade` (cadastro, [autenticação - 04]) chamasse o `auditoria`, formaria um ciclo entre módulos que o `ModularidadeTest` reprova. |
| `Papel` | Movido de `identidade.api` para `shared.api` | O `Ator` (shared) precisa dele, e o shared não pode depender do identidade. Só mudaram imports. |
| Coluna do ator | `ator_tipo` + `ator_sub uuid` nulo | O filtro por usuário (RF-40) usa `uuid`, como o resto do sistema. "SISTEMA" é um tipo, não um sub falso. |
| `entidade_tipo` | Enum `EntidadeAuditada` | O filtro da RF-40 fica sem erro de digitação; módulo novo acrescenta um valor. |
| Base da entidade | Exceção à `EntidadeBase`: `id` + `ocorrido_em`, sem `created_at`/`updated_at` | Registro nunca alterado. Registrada em `docs/convencoes-banco.md`. |
| `DELETE` > 5 anos usa `now()` do banco | Relógio do banco, não o `Clock` da aplicação | A regra está no trigger. Os dois relógios são o mesmo servidor no mesmo fuso (RN-62). |
| Força da imutabilidade | Trigger + `REVOKE`, com o limite documentado | O Flyway roda como `app_principal`, que é dono da tabela. Dono pode, com comando deliberado (`GRANT` a si mesmo, `ALTER TABLE ... DISABLE TRIGGER`), desfazer a proteção. Barra a aplicação e qualquer SQL comum. Um dono separado exigiria mudar o `init.sql` e o fluxo do Flyway. |

## Atenção ao atualizar um ambiente existente

- A `V4` roda sozinha na próxima subida do backend. Não há variável nova.
- Testes ou scripts que limpavam tabelas com `TRUNCATE` geral vão falhar na `auditoria`, de propósito.
- Para a [autenticação - 04]: o cadastro chama `auditoria.registrar(Ator.usuario(gestor.sub(), gestor.papel()), OperacaoAuditada.CADASTRO_USUARIO, EntidadeAuditada.USUARIO, ...)` dentro da transação local do cadastro.

## Como verificar

Ver `docs/testes/auditoria-01-registrar-auditoria-imutavel.md`.
