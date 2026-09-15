# rain-data-jdbc

For applications that use Spring Data JDBC: application-minted ids on insert and the column conversions rain's
persistence markers promise. rain itself registers no repository ([ADR 0002](../adr/0002-no-spring-data-inside-rain.md));
the application's repositories register exactly as they would without rain.

Add it when the application declares Spring Data JDBC repositories or uses `JdbcAggregateTemplate`.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
```

It brings [rain-persistence](persistence.md) and Boot's Spring Data JDBC module.

## What it contributes

`RainDataJdbcAutoConfiguration` — before Boot's `DataJdbcRepositoriesAutoConfiguration`, when
`JdbcAggregateTemplate` is on the classpath, in every role:

| Bean | Condition | What it is |
|---|---|---|
| `jdbcCustomConversions` | no other `JdbcCustomConversions` bean | `RainConverters.ALL` followed by the converters of every `JdbcConversionContribution` bean, in bean order |
| `assignIdCallback` | — | `AssignIdCallback` over the application's `IdGenerator` |

Ordering before Boot's repository auto-configuration makes these conversions take the slot Boot's own
`@ConditionalOnMissingBean` conversions would.

The statement timeout needs nothing from this module: Spring Data JDBC executes through a `JdbcTemplate`, and
rain-persistence sets `rain.persistence.statement-timeout` on every `JdbcTemplate` bean.

## Configuration

None of its own. `rain.persistence` applies ([rain-persistence](persistence.md#configuration)).

## API

| Type | What it does |
|---|---|
| `AssignIdCallback` | a `BeforeConvertCallback` that fills a null `UUID` `@Id` from `IdGenerator` through the entity's property accessor, so an immutable aggregate gets a copy with the id |
| `RainConverters.ALL` | the converters below |
| `JsonbWritingConverter` | `JsonbPayload` → `PGobject` of type `jsonb`; PostgreSQL refuses to cast an untyped string to `jsonb` |
| `JsonbReadingConverterFactory` | `PGobject` → the `JsonbPayload` subtype of the property |
| `WireEnumWritingConverter` | `WireEnum` → its `wire` string |
| `WireEnumReadingConverterFactory` | a string → the constant whose `wire` equals it; a string no constant declares is an `IllegalArgumentException`, never a null |
| `OffsetDateTimeToInstantConverter` | reading only: a `timestamptz` read as `OffsetDateTime` carries the session's offset, so it is normalised to `Instant` |
| `JdbcConversionContribution` | a bean that adds the application's converters next to rain's |

Stored JSON values are written with a mapper of their own (Jackson with the Kotlin module), not the application's HTTP
mapper, whose inclusion rules are about responses and would drop keys from a stored value.

```kotlin
enum class NoteStatus(
    override val wire: String,
) : WireEnum {
    DRAFT("draft"),
    PUBLISHED("published"),
}

data class NoteBody(
    val lines: List<String>,
    val pinned: Boolean,
) : JsonbPayload

@Table("notes")
data class Note(
    @Id val id: UUID? = null,
    val status: NoteStatus,
    val body: NoteBody,
    val createdAt: Instant,
    @Version val version: Int? = null,
)

interface NoteRepository : CrudRepository<Note, UUID>
```

```sql
CREATE TABLE notes (
  id         UUID PRIMARY KEY,
  status     TEXT NOT NULL CHECK (status IN ('draft', 'published')),
  body       JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  version    INT NOT NULL
);
```

### Insert or update

`AssignIdCallback` does not decide whether `save` inserts or updates. Spring Data JDBC asks `Persistable.isNew()`
when the aggregate implements it, then the `@Version` property (null, or 0 for a primitive, is new), and only then the
id. An aggregate with a version property is inserted with a pre-assigned id; the id is in hand before the insert, so
no `RETURNING` is needed.

## Error codes, health checks, schema, commands

None. Database failures raised by repositories are classified by rain-persistence's `DataAccessFaultTranslator`,
including `OptimisticLockingFailureException` as `409 stale_version`.

## What it does not do

- It registers no repository and enables no repository scanning; `@EnableJdbcRepositories` and
  `@AutoConfigurationPackage` would switch Boot's repository auto-configuration off for the application.
- It registers no writing converter for `Instant`; an `Instant` already writes as an absolute point in time.
- It does not read JPA annotations; Spring Data JDBC reads its own mapping annotations.
