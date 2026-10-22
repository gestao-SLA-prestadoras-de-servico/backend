# Convenções de banco

O schema é do Flyway (`src/main/resources/db/migration`). O Hibernate roda com `ddl-auto=validate`: só confere as entidades e a aplicação não sobe se houver divergência.

## Migrações

- Nome: `V{n}__descricao_em_snake_case.sql` (ex.: `V2__cria_chamado.sql`).
- **Migração mergeada nunca é editada.** O Flyway guarda o checksum de cada migração aplicada e recusa subir se ele mudar. Correção é sempre uma migração nova.
- Uma migração por mudança, criada junto com a entidade que a usa.

## Tabelas e colunas

- Nomes em `snake_case`, no singular (`chamado`, `prestadora`). No Java os campos ficam em camelCase; o Spring converte (`createdAt` → `created_at`).
- Toda entidade estende `shared.api.EntidadeBase`:

| Coluna | Tipo no banco | Tipo no Java |
| --- | --- | --- |
| `id` | `uuid PRIMARY KEY` | `UUID` (v7, gerado pelo Hibernate) |
| `created_at` | `timestamptz NOT NULL` | `Instant` |
| `updated_at` | `timestamptz NOT NULL` | `Instant` |

```sql
CREATE TABLE exemplo (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    status     varchar(30) NOT NULL,
    version    bigint NOT NULL
);
```

## Datas

- Toda data/hora é `timestamptz` no banco e `Instant` no Java. **Nunca** `timestamp` sem fuso nem `LocalDateTime`.
- O `validate` não pega `timestamp` sem fuso; quem pega é o teste `shouldStoreEveryDateTimeAsTimestamptz`.
- Data sem hora (ex.: feriado) é `date` / `LocalDate`.
- A hora atual vem sempre do `Clock` injetado (`Instant.now(clock)`), nunca de `Instant.now()`. A sessão JDBC e o Hibernate usam o fuso `FUSO_HORARIO`.
- Exceção: `created_at`/`updated_at` são preenchidos pelo Hibernate com a hora real, não com o `Clock`. São metadados de auditoria; prazo de SLA nunca é calculado a partir deles.

## Enums

- `@Enumerated(EnumType.STRING)` e coluna `varchar`. Nunca `ORDINAL`: reordenar o enum corromperia os dados gravados.
- Renomear um valor de enum exige migração que atualize as linhas existentes.

## Bloqueio otimista (RN-117)

Entidades alteradas por mais de um usuário/processo ao mesmo tempo declaram o próprio campo:

```java
@Version
private long version;
```

Coluna `version bigint NOT NULL`. Em conflito, o Hibernate lança `OptimisticLockException` em vez de sobrescrever a outra gravação.

## Número do chamado (RN-14)

Vem da sequência `chamado_numero_seq` (criada na `V1__baseline.sql`). Como não é a PK, a coluna usa default no banco e o Hibernate lê o valor gerado:

```sql
numero bigint NOT NULL UNIQUE DEFAULT nextval('chamado_numero_seq')
```

```java
@Generated
@Column(insertable = false, updatable = false)
private Long numero;
```

A numeração é única e crescente, mas pode ter buracos (rollback consome o número). Isso é aceito.
