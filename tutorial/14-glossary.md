# 14 — Glossary

Terms this tutorial uses without stopping to explain. Grouped by topic, with the
project-specific meaning where there is one.

---

## Architecture

**HLD / LLD** — High Level Design (boxes and arrows: services, databases, layers) vs. Low
Level Design (how one component actually works: classes, algorithms, data structures).
[06](06-hld.md) and [07](07-lld-availability-engine.md).

**Monolith** — one deployable unit containing all functionality. This project is one.
Opposite of microservices. Not a slur.

**Stateless** — the server keeps nothing between requests; all context arrives with the
request. What makes horizontal scaling possible. The day someone adds a `static Map` cache,
you've lost it.

**Layered architecture** — controller → service → repository, dependencies pointing only
downward.

**DTO (Data Transfer Object)** — a class that exists to cross a boundary. `UserResponse` is
a DTO; `UserDetails` is an entity. Keeping them separate means your database shape isn't
your API shape — and it's an access-control boundary, since a field not on the DTO can
never leak.

**Entity** — a Java class mapped to a database table (`@Entity`).

**Anaemic domain model** — entities that are pure getter/setter bags, with all behaviour in
services. `EventScheduler.isOrganisedBy()` and `addAudience()` are this project pushing
back against that.

**Coupling / cohesion** — how much two things depend on each other / how much one thing's
parts belong together. You want low coupling, high cohesion.
`UserDefaultAvailability` serving four roles at once is high coupling.

---

## Spring & Java

**Bean** — an object Spring creates and manages. `@Service`, `@RestController`,
`@Repository` classes are beans.

**Dependency injection (DI)** — Spring supplies a class's collaborators instead of the
class constructing them. `@Autowired`, or better, constructor parameters.

**IoC container** — the thing doing the injecting. "Inversion of Control": the framework
calls you, not the other way round.

**Auto-configuration** — Spring Boot configuring beans based on what's on the classpath.
`spring-boot-starter-web` present ⇒ Tomcat starts. You wrote no config for that.

**Component scanning** — Spring finding annotated classes under the
`@SpringBootApplication` class's package. Move that class and everything silently stops
being registered.

**Proxy** — a generated wrapper Spring puts around your bean to add behaviour
(`@Transactional`, `@Cacheable`). Why self-invocation bypasses those annotations, and why
Hibernate's lazy entities aren't quite the class you expect.

**Fat JAR / uber JAR** — one JAR containing your code, all dependencies, and an embedded
web server. `java -jar app.jar` and you're serving traffic.

**Annotation processor** — code that runs at compile time and generates more code. Lombok
is one; the methods exist in the `.class` file, not the `.java`.

**Checked vs. unchecked exception** — checked (`extends Exception`) must be declared or
caught; unchecked (`extends RuntimeException`) needn't be. Web apps use unchecked, because
there's nothing a controller can *do* about "user not found" except let the handler
translate it.

**`@ControllerAdvice`** — a class whose `@ExceptionHandler` methods apply to every
controller. One place to map exceptions to HTTP statuses.

**Bean Validation (JSR-380)** — `@NotNull`, `@Email`, `@Pattern`. Inert metadata unless
something triggers it — `@Valid` on a controller parameter, usually.

**Package-private** — no access modifier at all. **Narrower** than `protected`, which most
people get backwards. `private < package-private < protected < public`.

---

## Persistence

**ORM** — Object-Relational Mapping. Hibernate maps Java objects to SQL rows.

**JPA** — the Java *specification* for ORM (`jakarta.persistence.*`). Hibernate is the
*implementation*. Spring Data JPA is a layer of convenience on top of both.

**JPQL** — a query language over *entities and fields*, not tables and columns. `SELECT e
FROM EventScheduler e JOIN e.audiences aud` is JPQL; Hibernate translates it to SQL.

**Persistence context / session** — Hibernate's unit of work. Holds loaded entities,
tracks changes, decides when to flush.

**Managed vs. detached entity** — managed = attached to an open persistence context, so
changes are tracked. Detached = not, so mutations do nothing until merged.

**Dirty checking** — Hibernate comparing a managed entity to its loaded snapshot at flush
time and issuing an `UPDATE` automatically. You didn't call `save()`; it saved anyway.

**Flush** — when Hibernate actually sends the accumulated SQL. At transaction commit, or
before a query that might be affected. `save()` ≠ `INSERT`.

**Lazy vs. eager loading** — fetch the association on first access vs. immediately with the
parent. Defaults are inconsistent: `@OneToMany`/`@ManyToMany` are lazy;
`@ManyToOne`/`@OneToOne` are eager. Memorise it.

**`LazyInitializationException`** — touching a lazy association after the session closed.
Hidden here by `spring.jpa.open-in-view=true`, which is a default worth turning off in
production.

**N+1 query problem** — one query for a list, then one more per item. `addEventFor` does
this ([11](11-production-gaps.md#the-n1-query-problem)). Invisible at 10 rows, fatal at
10,000.

**Cascade** — operations propagating parent → child. `CascadeType.ALL` means deleting an
event deletes its audiences.

**Orphan removal** — removing a child *from the parent's collection* deletes the row.
Different from cascade-remove, which fires when the parent is deleted.

**Owning side** — in a bidirectional relationship, the side holding the foreign key. The
`mappedBy` side is a read view; setting only it persists nothing.

**`AttributeConverter`** — JPA's hook for mapping an arbitrary Java type to one column.
`UserDefaultAvailabilityConverter` uses it to store an object as JSON text.

**Migration** — a versioned, immutable SQL script applied automatically and exactly once
(Flyway, Liquibase). Not present in this project; badly needed.

**`ddl-auto`** — whether Hibernate modifies the schema. `none` here (correct for
production); `update` is convenient and dangerous.

---

## Databases

**Clustered index** — in InnoDB, the primary key *is* the physical row order, and every
secondary index stores a copy of the PK. Why a fat PK inflates the whole table.

**Leftmost prefix rule** — an index on `(a, b)` serves `WHERE a` and `WHERE a AND b`, but
not `WHERE b` alone. Column order in a composite index is a decision, not a formality.

**Unique constraint** — a uniqueness rule the database enforces. Also a **concurrency
control primitive**: often the cheapest correct fix for a read-then-write race, because
Java can't win that fight alone.

**Transaction** — a group of statements that all commit or all roll back. **ACID**:
Atomicity, Consistency, Isolation, Durability.

**Optimistic vs. pessimistic locking** — detect the conflict at write time and retry
(`@Version`) vs. lock the rows up front (`SELECT ... FOR UPDATE`). Optimistic wins under
low contention; pessimistic under high.

**Deadlock** — two transactions each holding what the other needs. Prevented by always
locking rows in a consistent order.

**Soft delete** — a `deleted_at` column instead of `DELETE`. Preserves history and makes
"who cancelled my meeting?" answerable. This project hard-deletes.

**Connection pool** — a set of reusable database connections (HikariCP here). Opening a
connection is expensive; a pool amortises it. Default max 10, and bigger is usually worse.

---

## Concurrency

**Race condition** — behaviour depending on the timing of concurrent operations.

**TOCTOU** — Time Of Check To Time Of Use. Check "the slot is free", then book it — but
another request booked it in between. The canonical concurrency bug, and the reason an
`if` doesn't prevent double-booking.

**Thread-safe** — correct when called concurrently. `SimpleDateFormat` is **not**, which
is why two `static` instances in this repo are latent production bugs.

**Thread pool** — Tomcat's fixed set of request-handling threads (default 200). A slow
request occupies one; 200 slow requests means the 201st user waits.

**Idempotent** — the same request applied twice has the same effect as once. `PUT` and
`DELETE` should be; `POST /event` here is not, so a client retry after a timeout creates
two meetings.

---

## Operations

**`ip:port`** — IP identifies the machine, port identifies the program on it.
[12](12-deploy-and-operate.md#what-ipport-actually-means).

**Bind address** — which network interface a socket listens on. `127.0.0.1` = this machine
only; `0.0.0.0` = every interface, **including the public internet**. Spring Boot defaults
to `0.0.0.0`.

**Reverse proxy** — nginx in front of your app: TLS, rate limiting, absorbing slow clients,
and load balancing across instances.

**TLS termination** — decrypting HTTPS at the proxy so the app speaks plain HTTP on
loopback.

**systemd unit** — the Linux service definition that starts your app on boot, restarts it
on crash, and runs it as an unprivileged user.

**Graceful shutdown** — on `SIGTERM`, stop accepting new connections, finish in-flight
requests, then exit. Without it every deploy drops somebody's request.

**Liveness vs. readiness** — "is the process wedged? restart it" vs. "can it serve traffic
right now? route to it". Confusing them causes restart storms.

**Horizontal vs. vertical scaling** — more instances vs. a bigger machine. Vertical first;
it's simpler and usually enough.

**Observability** — being able to answer questions about a running system: metrics, logs,
traces. You cannot operate what you cannot see.

**Correlation / trace ID** — a unique id per request, logged and returned to the client, so
"it failed at 3pm" becomes findable.

**Structured logging** — logs as JSON rather than prose, so they can be queried.

**Blast radius** — how much breaks when one thing fails. A monolith's is the whole product.

---

## Design

**SOLID** — Single responsibility, Open/closed, Liskov substitution, Interface segregation,
Dependency inversion. [10](10-design-principles.md).

**DRY** — Don't Repeat Yourself. Deduplicate **knowledge**, not characters. Two identical
blocks that change for different reasons are not duplication.

**YAGNI** — You Aren't Gonna Need It. Over-abstraction is at least as costly as
under-abstraction, and much harder to undo.

**Rule of Three** — write it concretely twice; abstract on the third, when you've seen
enough variation to know what the abstraction should be.

**Fail fast** — reject bad input at the boundary and crash on bad config at startup, rather
than producing a plausible wrong answer later.

**Silent failure** — the worst failure mode: wrong behaviour, no error. `findByEmailIn`
dropping unknown emails is one.

**Tell, don't ask** — `event.isOrganisedBy(user)` rather than pulling the audience list out
and deciding externally in three places.

**Make illegal states unrepresentable** — enforce a rule with the *type*, not a runtime
check. `UserUpdateRequest` has no `email` field, so "email can't change" cannot be
violated or forgotten.

**Principle of least astonishment** — behave the way a reader expects. `update()` silently
not copying `eventDate` violates it.

**Technical debt** — a shortcut taken knowingly, with interest paid later. Debt you
*chose* and documented (the README's timezone note) is fine; debt you don't know you have
is not.

**Scope cut (⚠️) vs. bug (🐞)** — a decision not to build something vs. code that doesn't do
what it intends. Telling them apart is most of what "engineering judgement" means.
