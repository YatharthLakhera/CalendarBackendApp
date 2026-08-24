# 06 — High Level Design

HLD answers: *what are the boxes, what are the arrows, and why is it split this way?*
LLD answers: *how does the hardest box actually work?* ([07](07-lld-availability-engine.md))

## The system today

```
                        ┌────────────────────────┐
   Browser / curl /     │                        │
   Postman / mobile ───▶│   :8080  (Tomcat,      │
                        │   embedded in the JAR) │
                        │                        │
                        │  ┌──────────────────┐  │
                        │  │  Spring Boot app │  │
                        │  └────────┬─────────┘  │
                        └───────────┼────────────┘
                                    │ JDBC (HikariCP pool)
                                    ▼
                        ┌────────────────────────┐
                        │   MySQL 8  :3306       │
                        │   calendar_db          │
                        └────────────────────────┘
```

That's it. **One process, one database, no cache, no queue, no load balancer, no auth
service.** Everything runs in a single JVM, listening on one TCP port.

That is not an insult — it is the correct architecture for this scope, and it is where
almost every successful system starts. A "microservices from day one" version of this
app would be strictly worse: more moving parts, more failure modes, more deployment
complexity, in exchange for scaling you don't need. Know when you're adding complexity
that buys nothing.

What *is* worth knowing is what the single-process design implies:

- **The app is stateless.** No session in memory, no in-memory cache, no local files that
  matter. Every request carries its own identity in the `userId` header. This is why you
  could run three copies behind a load balancer tomorrow with no code change — and it is
  the single most important property for horizontal scaling. Guard it.
- **The database is the only shared state**, so it is also the only place where
  concurrency correctness can be enforced. Every race condition in
  [11-production-gaps.md](11-production-gaps.md) is fixed in the database layer
  (constraints, transactions, locks), never in Java.
- **One process = one failure domain.** If the JVM dies, the whole product is down.
  [12-deploy-and-operate.md](12-deploy-and-operate.md) covers what you do about that.

## Internal architecture: the layered monolith

```
 ┌───────────────────────────────────────────────────────────────────────────┐
 │  controller/                      HTTP concerns only                      │
 │    UserController                 - map URL + method → a Java call        │
 │    AvailabilityController         - extract header/path/query/body        │
 │    EventSchedulerController       - NO business logic, NO SQL             │
 │    deserializer/  serializer/     - JSON ↔ Java conversion                │
 └──────────────────────────────┬────────────────────────────────────────────┘
                                │ passes DTOs (models/api) and primitives
 ┌──────────────────────────────▼────────────────────────────────────────────┐
 │  services/                        Business rules live here                │
 │    UserService                    - "the organiser is auto-accepted"      │
 │    AvailabilityService            - "custom overrides default"            │
 │    EventSchedulerService          - "only organisers may delete"          │
 │                                   - knows NOTHING about HTTP              │
 └──────────────────────────────┬────────────────────────────────────────────┘
                                │ passes entities (db/entity)
 ┌──────────────────────────────▼────────────────────────────────────────────┐
 │  db/repository/                   Persistence only                        │
 │    UserRepository                 - interfaces; Spring Data writes impls  │
 │    EventSchedulerRepository       - derived queries + @Query JPQL         │
 │    EventAudienceRepository                                                │
 │    UserCustomAvailabilityRepository                                       │
 └──────────────────────────────┬────────────────────────────────────────────┘
                                │ JPA / Hibernate
 ┌──────────────────────────────▼────────────────────────────────────────────┐
 │  db/entity/                       The schema, as Java                     │
 │  db/converter/                    JSON column mapping                     │
 └───────────────────────────────────────────────────────────────────────────┘

 Cross-cutting:
   utils/          pure functions, no Spring, no state (AvailabilityUtility et al.)
   models/api/     request & response DTOs — the public contract
   models/helper/  small shared value objects (AvailabilityBucket, User, AudienceRes)
   enums/          domain vocabulary shared by all layers
   exceptions/     domain failures, translated to HTTP by GlobalExceptionHandler
   GlobalExceptionHandler   @ControllerAdvice — one place for error → status mapping
```

### The rule that makes this work

**Dependencies point downward only.** A controller may call a service; a service may call
a repository. Nothing ever points back up — no service imports a controller, no entity
imports a DTO. Verify it yourself:

```bash
grep -rn "import com.calendar.app.controller" src/main/java/com/calendar/app/services/
# (no results — as it should be)
```

Why this matters more than it sounds: it means you can **test a service without HTTP**,
**change the API without touching business logic**, and **swap MySQL for Postgres by
changing the repository layer alone**. Layering isn't bureaucracy; it's the thing that
lets you change one part without understanding all the others.

### Where this project bends its own rule

Look at `AvailabilityController`:

```java
return availabilityService.getAvailabilityFor(
        userId,
        DateUtility.parseDate(fromDate),                 // ← parsing in the controller
        windowType,
        StringUtility.getEmailListFor(emailList)         // ← parsing in the controller
);
```

The controller parses a date string and splits a comma-separated list before calling the
service. Is that a layering violation?

**No — and being able to argue this is the point.** `"2024-12-11"` → `Date` and
`"a@x,b@y"` → `List<String>` are *input format* concerns, and input format is exactly the
controller's job. Doing it here means `AvailabilityService.getAvailabilityFor` takes a
real `Date` and a real `List<String>`, so it can't be handed a malformed string at all.

Contrast with a real violation, which would be the controller deciding *which* users to
load or *how* to intersect their slots. That never happens here.

**The transferable rule: push parsing and formatting outward, push decisions inward.**
The service should receive well-formed data and make judgements about it.

### Where the layering genuinely leaks

⚠️ `AvailabilityController.getDefaultAvailabilityFor` returns `UserDefaultAvailability` —
a class that lives in the `db` package and is *persisted* (as a JSON column). So the
**database's storage format is the API's response format**. Change the column layout and
you break every client; add an API field and you change what's in the database.

Everywhere else this project is careful: `UserDetails` (entity) → `UserResponse` (DTO),
`EventScheduler` (entity) → `EventSchedulingResponse` (DTO). This one endpoint skips the
translation, and it's the one place where a schema change becomes an API break.

Notice also that `UserDefaultAvailability` carries `@JsonDeserialize` annotations *and*
lives in `db/` *and* is used as a request body *and* as a response body *and* as a stored
format — four roles, one class. When a class has four roles, a change for any one of them
risks the other three. That's what "coupling" concretely means.

## Request → response, as a sequence

```
 Client        Controller          Service              Repository        MySQL
   │               │                  │                     │              │
   │─ GET /avail ─▶│                  │                     │              │
   │               │─ parseDate ──┐   │                     │              │
   │               │◀─────────────┘   │                     │              │
   │               │─ getAvailabilityFor(userId, date, ...) ▶│              │
   │               │                  │─ findById(organiser)▶│──── SELECT ─▶│
   │               │                  │◀────────────────────│◀─────────────│
   │               │                  │─ findByEmailIn ─────▶│──── SELECT ─▶│
   │               │                  │◀────────────────────│◀─────────────│
   │               │                  │─ findByParticipant… ▶│──── SELECT ─▶│
   │               │                  │◀────────────────────│◀─────────────│
   │               │                  │─ findByUserIdsAnd… ─▶│──── SELECT ─▶│
   │               │                  │◀────────────────────│◀─────────────│
   │               │                  │                     │              │
   │               │      AvailabilityUtility.availabilityResponseBuilder  │
   │               │      (pure computation — no I/O, no Spring)           │
   │               │                  │                     │              │
   │               │◀── AvailabilityResponse ───────────────│              │
   │◀── JSON ──────│                  │                     │              │
```

Note the shape of that service method: **gather all the data, then compute.** Four queries
up front, then one pure function. It is not "query inside a loop inside the algorithm."
That separation is why `AvailabilityUtility` can be unit-tested with plain objects and no
database at all — and it's the single best structural decision in this codebase.

Compare with `EventSchedulerService.addEventFor`, which queries **inside** a loop. Same
codebase, opposite habit. See [10-design-principles.md](10-design-principles.md).

## Why a monolith here, and when you'd split

You will be asked this in interviews and in real design reviews. The honest answer:

**Keep the monolith while:**
- One team owns everything
- The whole thing deploys together anyway
- A transaction can span any two tables (this app's reschedule flow relies on that)
- Traffic fits comfortably in a few instances

**Consider splitting when:**
- Different parts need wildly different scaling (the availability engine is CPU-bound; the
  user CRUD is trivial — that's the first natural seam here)
- Separate teams need to deploy independently without coordinating
- A failure in one area must not take down the others

For this app: **do not split.** Adding a network hop between "load the users" and
"intersect their slots" would make it slower, less reliable, and much harder to reason
about, in exchange for nothing.

## What you'd add first, in order

If this project got real users tomorrow, the sequence would be roughly:

1. **Authentication** — nothing else matters if anyone can be anyone
   ([11](11-production-gaps.md#no-authentication-or-authorisation))
2. **Schema migrations** (Flyway) — you cannot safely change the DB without them
3. **The missing constraints** — unique email, unique `(user_id, date)`
4. **Tests** — there is exactly one test and it asserts nothing
5. **Health checks + metrics** (Spring Boot Actuator) — you cannot operate what you cannot see
6. **Availability check on booking** — the product's core promise
7. **Caching** — only *after* measuring; the README already notes it isn't needed yet

Notice that items 1–5 are not features. **Most of the work that separates a demo from a
production service is invisible to users.** That is the single biggest surprise for
someone shipping their first real backend.

## Deployment view (preview of [12](12-deploy-and-operate.md))

Today:

```
your laptop:  java -jar app.jar   →   localhost:8080   →   localhost:3306
```

Minimally realistic:

```
             ┌──────────────────────────────────────────────┐
  Internet ─▶│ nginx :443  (TLS termination, rate limiting) │
             └───────────────────┬──────────────────────────┘
                                 │ proxy to 127.0.0.1:8080
             ┌───────────────────▼──────────────────────────┐
             │ systemd service: java -jar app.jar           │
             │  - restarts on crash                         │
             │  - config from environment variables         │
             │  - binds to 127.0.0.1 only, never 0.0.0.0    │
             └───────────────────┬──────────────────────────┘
                                 │ private network
             ┌───────────────────▼──────────────────────────┐
             │ MySQL :3306  (not reachable from internet)   │
             └──────────────────────────────────────────────┘
```

## Checkpoint

- [ ] Draw the three layers from memory and state the dependency rule
- [ ] Explain why the controller parsing a date is fine but returning `UserDefaultAvailability` isn't
- [ ] Explain why the app is stateless and why that matters
- [ ] Give one concrete reason *not* to split this into microservices
