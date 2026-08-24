# 04 — Database Schema and ORM Mapping

Four tables. Read `db_schema.sql` alongside this file.

## The schema

```
┌─────────────────────────────┐
│ user_details                │
├─────────────────────────────┤
│ user_id      binary(16) PK  │◄───────┐
│ name         varchar(128)   │        │
│ email        varchar(128)   │        │
│ default_availability  json  │        │
└─────────────────────────────┘        │
          ▲                            │
          │ FK (UCA_user_id_fk)        │ FK (EA_user_id_fk)
          │                            │
┌─────────┴───────────────────┐   ┌────┴──────────────────────────┐
│ user_custom_availability    │   │ event_audience                │
├─────────────────────────────┤   ├───────────────────────────────┤
│ id           int AI  PK     │   │ id         int AI  PK         │
│ user_id      binary(16) FK  │   │ event_id   binary(16) FK ─────┼──┐
│ availability_date  date     │   │ user_id    binary(16) FK      │  │
│ time_zone    enum('IST')    │   │ role       enum(ORGANISER,    │  │
│ is_available tinyint(1)     │   │                  PARTICIPANT) │  │
│ availability varchar(512)   │   │ status     enum(PENDING,      │  │
│                             │   │             ACCEPTED, MAYBE,  │  │
│ KEY (user_id,               │   │             DECLINED)         │  │
│      availability_date)     │   │ KEY (event_id, user_id)       │  │
└─────────────────────────────┘   └───────────────────────────────┘  │
                                                                     │
                              ┌──────────────────────────────────────┘
                              ▼
                  ┌─────────────────────────────┐
                  │ event_scheduler             │
                  ├─────────────────────────────┤
                  │ event_id    binary(16) PK   │
                  │ title       varchar(128)    │
                  │ description text            │
                  │ event_date  date            │
                  │ start_time  varchar(4)      │
                  │ end_time    varchar(4)      │
                  │ time_zone   enum('IST')     │
                  │ KEY (event_date)            │
                  └─────────────────────────────┘
```

`event_audience` is a **join table with attributes**: it resolves the many-to-many between
users and events, and it carries `role` and `status`, which belong to the *relationship*
(you are an organiser *of this event*), not to the user or the event alone. Recognising
when a relationship needs its own entity is a core modelling skill. If it only linked two
IDs, JPA's `@ManyToMany` would suffice; because it has attributes, it must be a first-class
entity with `@OneToMany` / `@ManyToOne` on both sides.

## Design decision 1: UUID as `binary(16)`

```sql
user_id binary(16) not null primary key
```

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID userId;
```

A UUID is 128 bits. You can store it three ways:

| Storage | Bytes/row | Human-readable in SQL? |
|---|---|---|
| `char(36)` — `"1728dfe4-a197-..."` | 36 | Yes |
| `binary(16)` | 16 | No |
| `bigint` auto-increment (not a UUID) | 8 | Yes |

`binary(16)` is less than half the size of `char(36)`. On the primary key that matters
more than it looks: in InnoDB the PK is the clustered index, and **every secondary index
stores a copy of the PK**. A fatter PK inflates every index in the table and every page
of RAM the buffer pool spends on them.

### Why you can't just `SELECT` by UUID

The README flags this and it will be your first confusing moment in MySQL:

```sql
SELECT * FROM user_details WHERE user_id = '1728dfe4-a197-47bf-9c4c-c80011c58f9e';
-- returns nothing
```

The column holds 16 raw bytes; you compared it to a 36-character string. You must convert:

```sql
SELECT * FROM user_details
WHERE user_id = UNHEX(REPLACE('1728dfe4-a197-47bf-9c4c-c80011c58f9e','-',''));
```

And to read one back out:

```sql
SELECT LOWER(CONCAT_WS('-',
         SUBSTR(HEX(user_id),1,8),  SUBSTR(HEX(user_id),9,4),
         SUBSTR(HEX(user_id),13,4), SUBSTR(HEX(user_id),17,4),
         SUBSTR(HEX(user_id),21))) AS user_id, name, email
FROM user_details;
```

Save that query. You'll want it every time you debug.

⚠️ **The cost nobody mentions until it hurts.** Random UUIDs (v4) as a clustered primary
key cause **index fragmentation**: rows insert at random positions in the B-tree instead
of appending at the end, so InnoDB splits pages constantly and write throughput degrades
as the table grows. At this project's scale it's irrelevant. At ten million rows it is a
real incident. The modern fixes are UUIDv7 (time-ordered, so inserts append) or a
`bigint` PK with the UUID as a separate unique "public ID" column. Knowing that this
trade-off *exists* is the point.

## Design decision 2: time as `varchar(4)`

```sql
start_time varchar(4) not null   -- "1000" means 10:00
```

```java
private String startTime;   // "1000"
```

Not `TIME`, not `int`, not `LocalTime`. A four-character string.

**What it buys:** the exact same format flows through the API (`"1000"`), the availability
strings (`"1000:1100"`), and the DB with zero conversion. `AvailabilityBucket` parses it
once with `Integer.parseInt`, and `StringUtils.leftPad(..., 4, '0')` puts the zeros back
(`900` → `"0900"`). Simple, debuggable, no timezone semantics smuggled in.

**What it costs:**
- The database cannot validate it. `"9999"`, `"abcd"`, `""` all fit in `varchar(4)`.
- You cannot do time arithmetic in SQL. No `TIMEDIFF`, no `BETWEEN` on real times, no
  "events longer than 30 minutes" query without parsing strings.
- Sorting is lexicographic, which happens to match numeric order **only because** every
  value is zero-padded to exactly 4 characters. Store `"900"` once and ordering silently
  breaks.
- The whole thing is timezone-naive by construction.

The honest verdict: **acceptable for this project, wrong for a real calendar.** A real
calendar stores UTC instants (`TIMESTAMP` / `datetime` in UTC) plus the originating
timezone, because "10:00" is meaningless without knowing whose 10:00. Read
[11-production-gaps.md](11-production-gaps.md#timezones-are-not-implemented) — the
timezone gap and the `varchar(4)` choice are the same decision viewed from two angles.

## Design decision 3: the JSON column and `@Convert`

```sql
default_availability json default null
```

```java
@Convert(converter = UserDefaultAvailabilityConverter.class)
private UserDefaultAvailability defaultAvailability;
```

`UserDefaultAvailability` is **not an entity** — it's a plain class with seven string
fields (`monday`..`sunday`) and a `timeZone`. It's stored as one JSON blob in one column:

```json
{"timeZone":"IST","monday":"1000:1100;1300:1800","tuesday":"1000:1100", ...}
```

`UserDefaultAvailabilityConverter` implements `AttributeConverter<UserDefaultAvailability, String>`
and does the obvious thing with a Jackson `ObjectMapper`:

```java
public String convertToDatabaseColumn(UserDefaultAvailability attribute)   // object → JSON text
public UserDefaultAvailability convertToEntityAttribute(String dbData)     // JSON text → object
```

`@Converter` + `@Convert` is the JPA-standard way to map an arbitrary Java type to a
single column. Same mechanism you'd use for encrypting a column at rest, or storing an
enum as a custom code.

### The trade-off you're actually making

**Alternative A (chosen): one JSON column.**
- One row per user. Reading a user reads their whole schedule — no join, no N+1.
- The schema never changes when you add a field to the JSON.
- ❌ You cannot query *into* it with JPA. "Find everyone available Monday at 10am" is
  impossible without MySQL JSON functions written by hand, and it can't use a normal index.
- ❌ The database enforces nothing. Garbage JSON in the column is only discovered when
  Java tries to parse it — and `convertToEntityAttribute` then throws a
  `RuntimeException`, which surfaces as a 500 on an unrelated endpoint.
- ❌ No history. Overwriting the blob loses the previous schedule entirely.

**Alternative B: a `user_default_availability` table** with `(user_id, day_of_week,
start_time, end_time)` — one row per range.
- Queryable, indexable, constrainable.
- ❌ 5–10 rows per user, a join on every read, more code.

For "show me one user's schedule", A wins and is the right call here. The moment a
product manager asks "who's free Monday morning?", A loses badly. **That question — *what
queries will I need?* — is what actually decides normalised vs. denormalised,** far more
than any abstract rule about normal forms.

### 🐞 Two small landmines in the converter

1. `private static final ObjectMapper objectMapper = new ObjectMapper();` — a *plain*
   `ObjectMapper`, not Spring's configured one. Any Jackson configuration you add later
   via `application.properties` or a `@Bean` (date formats, naming strategy,
   `FAIL_ON_UNKNOWN_PROPERTIES`) applies to your API but **not** to this column. Two
   different JSON dialects in one app is a subtle source of "why does it work over HTTP
   but not from the DB?".
2. Both methods wrap failures in a bare `RuntimeException`, so any corrupt row becomes a
   `500` with a stack trace from deep inside Hibernate. (`ObjectMapper` is thread-safe for
   read/write once configured, so `static` is fine — that part is correct.)

## Design decision 4: enums in two places at once

```sql
time_zone enum('IST') not null
role      enum('ORGANISER','PARTICIPANT') not null default 'PARTICIPANT'
status    enum('PENDING','ACCEPTED','MAYBE','DECLINED') not null default 'PENDING'
```

```java
@Enumerated(EnumType.STRING)
private EventStatus status;
```

**`@Enumerated(EnumType.STRING)` is the single most important JPA detail on this page.**

The default is `EnumType.ORDINAL`, which stores the enum's **position** (0, 1, 2...). If
anyone ever reorders `EventStatus` from `{PENDING, ACCEPTED, MAYBE, DECLINED}` to
`{PENDING, ACCEPTED, DECLINED, MAYBE}`, every existing row silently changes meaning:
every `MAYBE` becomes `DECLINED`. There's no error, no warning, no migration — your data
is just wrong. **Always use `EnumType.STRING`.** This project gets it right everywhere;
notice that and keep the habit.

The DB-level `ENUM` gives you a second layer: MySQL rejects a value the application never
should have sent. Defence in depth — the same rule enforced independently in two places.

The cost: adding a status now requires **both** a Java change and an `ALTER TABLE`. That's
a feature (nothing drifts) and a friction (two-step deploys). And note that
`enum('IST')` guarantees the timezone gap can't be quietly half-implemented — you'd have
to migrate the column to add `PST`.

## Design decision 5: the indexes (and the ones that aren't there)

```sql
key `UCA_user_id_date_key` (user_id, availability_date)
key `ES_event_date_key` (event_date)
key `EA_event_id_user_id_key` (event_id, user_id)
```

Each exists to serve a specific query. Trace them:

- `UCA_user_id_date_key` → `findByUserIdsAndDateRange` (`WHERE user_id IN (...) AND
  availability_date BETWEEN ...`). Column order matters: an index on `(a, b)` can serve
  `WHERE a` and `WHERE a AND b`, but **not** `WHERE b` alone. Leftmost-prefix rule. It's
  correct here because every query filters on `user_id` first.
- `ES_event_date_key` → the `BETWEEN :startDate AND :endDate` in
  `findByParticipantUserIdsAndDateRange`.
- `EA_event_id_user_id_key` → `findByEventAndUser` in the RSVP flow.

Now the gaps:

- 🐞 **No index on `user_details.email`**, yet `findByEmail` and `findByEmailIn` run on
  every search and every booking. Today with 10 users MySQL scans the table in
  microseconds. At 100k users, every event booking does a full table scan per participant.
  Add `KEY (email)` — or better, `UNIQUE KEY (email)`, which indexes *and* fixes the
  duplicate-user bug from [03-api-reference.md](03-api-reference.md#post-user--create-a-user)
  in one line.
- 🐞 **No `UNIQUE KEY` on `user_custom_availability (user_id, availability_date)`** — only
  a plain `KEY`. That's the race condition described in the API reference. A unique
  constraint is not just data hygiene: **it is a concurrency control primitive**, and
  frequently the cheapest correct fix for a read-then-write race.
- ⚠️ `event_audience` has no index on `user_id` alone. "All events for user X" must use
  the `event_audience` → `event_scheduler` join in a direction the index doesn't help.

**The transferable lesson:** an index is not decoration. Every index is a bet that a
specific query will be run often, paid for with slower writes and more disk. Write the
query first, then the index that serves it — and when you see an index, ask which query
it's for. If you can't answer, it may not need to exist.

## Entity-by-entity mapping

### `UserDetails` → `user_details`

```java
@Data @Entity @NoArgsConstructor @Table(name = "user_details")
public class UserDetails {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID userId;      // → user_id
    private String name;      // → name
    private String email;     // → email
    @Convert(converter = UserDefaultAvailabilityConverter.class)
    private UserDefaultAvailability defaultAvailability;  // → default_availability
}
```

`userId` → `user_id` with no `@Column` annotation because Hibernate's default
`CamelCaseToUnderscoresNamingStrategy` does it automatically. Nice, until someone renames
a Java field and silently breaks a column mapping — the mapping is implicit, so nothing
in the code says "this must stay `user_id`". Explicit `@Column(name = "user_id")` costs
one line and removes that risk. Reasonable people disagree; know that you're choosing.

`@NoArgsConstructor` is **required**, not stylistic: JPA needs a no-arg constructor to
instantiate entities via reflection. The class also has a hand-written
`UserDetails(UserRequest)` constructor, and in Java, declaring any constructor removes
the implicit default one — hence the annotation.

### `EventScheduler` → `event_scheduler`

```java
@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
private List<EventAudience> audiences;
```

Three settings, three distinct meanings — this is worth memorising:

- **`mappedBy = "event"`** — *the other side owns the foreign key.* `EventAudience.event`
  holds the `event_id` column. Without `mappedBy`, JPA assumes a join table and generates
  a schema that doesn't exist. The **owning side** is the one that writes the FK; the
  `mappedBy` side is a read/convenience view. Set the child's `event` field, not just the
  parent's list, or nothing persists — which is exactly why
  `EventScheduler.addAudience(...)` constructs `new EventAudience(this, ...)`, passing
  `this` so both directions are wired.
- **`cascade = CascadeType.ALL`** — persist/merge/remove propagate parent → children. Save
  the event, its audiences are saved. Delete the event, they're deleted. That's why
  `deleteEventFor` doesn't touch `EventAudienceRepository` at all.
- **`orphanRemoval = true`** — remove a child from the list and it is **deleted from the
  DB**, not merely unlinked. This is what makes `removeAllAudiences()` work during
  reschedule. Cascade-remove and orphan-removal are different: cascade fires when the
  *parent* is deleted; orphan removal fires when a child is *detached from the collection*.

`@Temporal(TemporalType.DATE)` on `eventDate` tells Hibernate to map `java.util.Date` to a
SQL `DATE` (no time component) rather than a `TIMESTAMP`. It's needed only because the
project uses the legacy `java.util.Date`; with `LocalDate` it would be unnecessary. See
[09-java-gaps.md](09-java-gaps.md#javautildate-in-2024).

### `EventAudience` → `event_audience`

```java
@ToString.Exclude
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "event_id")
@JsonBackReference
private EventScheduler event;

@OneToOne
@JoinColumn(name = "user_id")
private UserDetails user;
```

Four annotations doing four jobs, each preventing a specific disaster:

- **`@ManyToOne(fetch = FetchType.LAZY)`** — don't load the parent event just because you
  loaded an audience row. `@ManyToOne` defaults to `EAGER`, which is a notorious
  performance trap: loading 100 audience rows would fire 100 event queries. `@OneToMany`
  defaults to `LAZY`. Those inconsistent defaults are a JPA design wart; memorise them.
- **`@ToString.Exclude`** — `@Data` generates `toString()`. Event's `toString` prints its
  audiences; each audience's `toString` would print its event; **infinite recursion →
  `StackOverflowError`**. And because the services log entities
  (`log.info("eventScheduler : {}", eventScheduler)`), this crash would happen *in a log
  statement*. Excluding one side of every bidirectional relationship from `toString` is
  mandatory, not optional.
- **`@JsonBackReference`** — the identical problem during JSON serialization. It marks
  this side as the "back" of the reference so Jackson omits it, breaking the cycle.
- **`@OneToOne` on `user`** — ⚠️ this is modelled wrongly. One user attends *many* events,
  so it should be `@ManyToOne`. It works today because both generate the same `user_id`
  FK column and the code never navigates user → audiences. But `@OneToOne` implies a
  uniqueness that doesn't exist, and it changes how Hibernate can optimise fetching. A
  wrong annotation that happens to produce right behaviour is a trap for the next person.

Then:

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
    return Objects.equals(id, ((EventAudience) o).id);
}

@Override
public int hashCode() { return getClass().hashCode(); }
```

This looks bizarre and is (mostly) correct. Full explanation in
[09-java-gaps.md](09-java-gaps.md#why-that-strange-equalshashcode-exists) — it's one of
the best "you'd never Google this" items in the repo.

### `UserCustomAvailability` → `user_custom_availability`

```java
private UUID userId;        // ← a raw UUID, not @ManyToOne UserDetails
private boolean isAvailable;
```

Note the inconsistency: `EventAudience` maps its user as an **object reference**
(`@OneToOne UserDetails user`), while `UserCustomAvailability` stores a **raw UUID**. Two
different modelling styles for the same relationship in the same codebase.

The raw-UUID style is arguably better here — `AvailabilityUtility` only ever needs to
*group by* user ID (`Collectors.groupingBy(UserCustomAvailability::getUserId)`), never to
load the user, so an association would only invite accidental lazy loads. But the
inconsistency itself has a cost: a reader can no longer predict the pattern, and
`findByUserIdsAndDateRange` had to be hand-written as `@Query` partly because of it.

`isAvailable` → column `is_available`: the naming strategy sees the field name
`isAvailable`, not the getter. But **Jackson** sees the getter `isAvailable()` and
publishes the property as `available`. Field name, column name, and JSON name all differ.
That's [the trap](09-java-gaps.md#the-isavailable-trap).

## Checkpoint

- [ ] Query `user_details` by UUID from the MySQL CLI, and print the UUIDs back as text
- [ ] Explain why `@Enumerated(EnumType.STRING)` is not optional
- [ ] Explain what `orphanRemoval = true` does that `CascadeType.ALL` doesn't
- [ ] Point at each index and name the query it serves
- [ ] Name the two indexes that should exist and don't, and the bug each one would fix
