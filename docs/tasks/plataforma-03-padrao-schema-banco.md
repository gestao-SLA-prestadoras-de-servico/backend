# [plataforma - 03] Definir o padrão de schema do banco

> Requisitos: RN-14 (sequência do número do chamado), RN-117 (coluna de versão) · Depende de: [plataforma - 01]

## Contexto

O Flyway já estava no `pom.xml` (plataforma-01), mas não havia migração: o schema era criado pelo Hibernate (`JPA_DDL_AUTO=update` em dev, `create-drop` em test). Antes da primeira entidade, o schema passa a ser só do Flyway, com um padrão único de id, datas, enums e versão.

## Problemática: critérios de aceite × solução

| Critério de aceite | Como foi resolvido |
| --- | --- |
| A aplicação não sobe se as entidades divergirem do schema | `spring.jpa.hibernate.ddl-auto=validate` fixo em `application.properties` (a variável `JPA_DDL_AUTO` saiu dos `.envs/*.example`). O perfil de teste deixou de usar `create-drop`, então o teste de integração roda Flyway + `validate` como em produção. |
| Toda data/hora é gravada como `timestamptz` | `EntidadeBase` usa `Instant`, que o Hibernate mapeia para `timestamptz`. Como o `validate` **aceita** `timestamp` sem fuso para `Instant` (testado), o teste `shouldStoreEveryDateTimeAsTimestamptz` reprova qualquer coluna `timestamp`/`time` sem fuso no schema `public`. |
| A sequência `chamado_numero_seq` existe | Criada em `V1__baseline.sql`; verificada por `shouldCreateChamadoNumeroSequence`. |
| Existe um documento curto de convenções | `docs/convencoes-banco.md`. |

## Passos técnicos realizados

- [x] **Habilitar Flyway e criar `V1__baseline.sql`**
  O Flyway roda automaticamente com o starter. A baseline só cria a sequência: tabelas entram junto com as entidades, cada uma na sua migração.

- [x] **Definir chave primária UUID, `created_at`/`updated_at` e coluna `version` nas entidades com concorrência**
  `shared.api.EntidadeBase` (`@MappedSuperclass`): `id` UUID v7 (`@UuidGenerator(style = VERSION_7)`), `createdAt`/`updatedAt` com `@CreationTimestamp`/`@UpdateTimestamp`. O `@Version` é declarado direto em cada entidade com concorrência.

- [x] **Definir enums como texto (`@Enumerated(EnumType.STRING)`)**
  Convenção no doc; coluna `varchar`.

- [x] **Criar a sequência `chamado_numero_seq`**
  Buracos na numeração são aceitos (rollback consome o número). Uso na futura entidade `Chamado` documentado: `DEFAULT nextval(...)` + `@Generated`.

- [x] **Escrever `docs/convencoes-banco.md` (migração mergeada nunca é editada)**

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Geração do UUID | v7 no Hibernate | Ordenado no tempo: inserts não espalham o índice da PK. O PostgreSQL 17 não tem `uuidv7()`. |
| `@Version` | Declarado em cada entidade, não na base | Só as entidades com concorrência precisam; é um campo. |
| `ddl-auto` | Fixo em `validate`, sem variável | Com `update` em dev, o Hibernate competiria com o Flyway. |
| `hibernate.jdbc.time_zone` | Não configurado aqui (configurado na plataforma-04) | `Instant` em `timestamptz` já é sempre UTC. |

## Atenção ao atualizar um ambiente existente

Remova `JPA_DDL_AUTO` do seu `.envs/.env.*` local (não é mais lido). O Flyway exige o schema `public` vazio na primeira execução; como ainda não havia entidades, os bancos existentes já estão vazios.

## Como verificar

Ver `docs/testes/plataforma-03-padrao-schema-banco.md`.
