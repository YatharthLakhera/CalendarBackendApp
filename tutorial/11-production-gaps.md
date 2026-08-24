# 11 — Production Gaps

Everything that would break, leak, or bite if this shipped to real users tomorrow.

This is the file to reread whenever you finish a feature and think you're done. **Most of
the work that separates a demo from a production service is invisible to users**, and
almost none of it is on the ticket.

Ordered by what would hurt first.

---

## No authentication or authorisation

```java
@GetMapping("/user")
public UserResponse getUserFor(@RequestHeader("userId") UUID userId)
```

The caller asserts who they are and the server believes them. No password, no token, no
signature, no session. **The user ID is being used as a credential**, and it isn't one.

Worse: `GET /user/search?email=...` needs no header at all and hands out any user's UUID
for their email address. So the "credential" is publicly queryable. Chain the two and any
stranger can:

```bash
curl "$BASE/user/search?email=ceo@company.com"      # → their UUID
curl "$BASE/availability/2025-01-14/WEEK" -H "userId: <their-uuid>"   # → their calendar
curl -X DELETE "$BASE/event/<any-event>" -H "userId: <their-uuid>"    # → cancel their meetings
```

### What it should be

**Authentication** (who are you?) — a signed token, verified on every request:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

A JWT is signed with a key only the server holds. Tamper with the user ID inside and the
signature fails. Add `spring-boot-starter-security`, a filter that validates the token,
and take the identity from Spring's `SecurityContext` — **never from a request parameter**:

```java
@GetMapping("/user")
public UserResponse getUserFor(@AuthenticationPrincipal AppUser caller) { ... }
```

That change alone removes `@RequestHeader("userId")` from every endpoint and makes
impersonation impossible by construction.

**Authorisation** (are you allowed?) — this project *has* the logic
(`EventScheduler.isOrganisedBy`) and applies it inconsistently: enforced on delete and
reschedule, forgotten on `GET /event/{eventId}`, which lets anyone with an event UUID read
its title, description, and every attendee's name, email, and RSVP.

**The lesson generalises well beyond auth:** a rule enforced at three call sites and
forgotten at the fourth is the normal outcome of putting rules in call sites. Enforce it in
one place that all paths must pass through.

---

## Double-booking is not prevented

`EventSchedulerService.addEventFor` never consults `AvailabilityService`. You can book:
over an existing meeting, outside everyone's declared hours, on a day someone marked
unavailable, at 03:00, with `endTime` before `startTime`.

**The availability API is advisory; the booking API enforces nothing.**

### Why the obvious fix is wrong

```java
// tempting, and still broken:
if (!availabilityService.isFree(participants, date, start, end)) {
    throw new BadRequestException("Slot not available");
}
eventSchedulerRepository.save(event);
```

Two requests can both run the check, both pass, and both save:

```
  Request A                    Request B
  ─────────                    ─────────
  check 10:00-11:00 → free
                               check 10:00-11:00 → free      ← A hasn't committed yet
  save event                   save event
  ✅ 200                        ✅ 200        → two meetings, same slot
```

This is a **TOCTOU** bug — Time Of Check To Time Of Use — and it is the single most
important concurrency pattern to recognise. It appears anywhere you check-then-act on
shared state: seat booking, inventory, username registration, balance transfers. Your
laptop will never reproduce it; production will hit it within a day.

### Fixes that actually work

**1. A database constraint** — the cheapest correct answer whenever the rule can be
expressed as uniqueness. It can't express interval overlap in MySQL (PostgreSQL has
exclusion constraints; MySQL doesn't), but if the product accepted fixed 30-minute slots,
`UNIQUE (user_id, event_date, slot_index)` would end the problem forever. **Reshaping the
problem so a constraint can express it is a legitimate and underused move.**

**2. Pessimistic locking** — make the readers queue:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM UserDetails u WHERE u.userId IN :ids ORDER BY u.userId")
List<UserDetails> lockParticipants(@Param("ids") List<UUID> ids);
```

Inside one `@Transactional` method: lock every participant row, then check, then insert.
`ORDER BY` matters — **locking rows in a consistent order across all code paths is what
prevents deadlocks.** Two transactions locking A-then-B and B-then-A will deadlock.

**3. Optimistic locking** — add `@Version` to the entity and let the loser retry. Better
under low contention; needs retry logic.

For this app, option 2 inside a single transaction is the right shape. **And whichever you
choose, the check and the write must be in the same transaction** — that's the part people
forget.

---

## No schema migrations

`ddl-auto=none` plus a hand-run `db_schema.sql` (which exists in **two copies**, root and
`src/main/resources/`, already a drift risk).

Questions you cannot answer today:
- Which version of the schema is on the production server?
- How do I add a column without downtime?
- How do I roll back a bad change?
- How does a new developer get a database matching production?

### Flyway, in five minutes

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

```
src/main/resources/db/migration/
    V1__initial_schema.sql
    V2__add_unique_email.sql
    V3__add_deleted_at_to_event.sql
```

On startup Flyway checks its `flyway_schema_history` table, applies anything unapplied, in
order, once, and records a checksum. Now the schema is **version-controlled, reviewable in
pull requests, identical everywhere, and applied automatically on deploy.**

The rule that makes it work: **migrations are append-only and immutable.** Never edit
`V2` after it has run anywhere — write `V4`. Flyway's checksums will refuse to start if you
do, which is a feature.

### The migrations this project needs today

```sql
-- V2: stop duplicate users (also gives the index findByEmail needs)
ALTER TABLE user_details ADD UNIQUE KEY uk_user_email (email);

-- V3: make the custom-availability upsert race-safe
ALTER TABLE user_custom_availability
  DROP INDEX UCA_user_id_date_key,
  ADD UNIQUE KEY uk_uca_user_date (user_id, availability_date);
```

Two `ALTER`s that fix two bugs Java couldn't fix as reliably. **Constraints are not
paperwork; they're the only concurrency-safe place to enforce uniqueness.**

---

## There are no tests

```java
@SpringBootTest
class CalendarAppApplicationTests {
    @Test
    void contextLoads() { }
}
```

One test. It asserts nothing. It only proves Spring can start — which is genuinely useful
(it catches missing beans, bad config, broken derived queries) but it is not testing.

**Every bug in this tutorial would have been caught by a unit test of `AvailabilityUtility`,
and that class is *already* perfectly testable** — pure, static, no Spring, no database.
That's the frustrating part: the hard work of making it testable was done, and nobody
collected the payoff.

### The test that finds the off-by-one

```java
@Test
void mondayRequestUsesMondaySchedule() {
    UserDefaultAvailability defaults = new UserDefaultAvailability();
    defaults.setMonday("0900:0930");     // every day DIFFERENT — this is the whole trick
    defaults.setTuesday("1000:1030");
    defaults.setWednesday("1100:1130");
    // ... thursday..sunday, all distinct

    UserDetails user = new UserDetails();
    user.setUserId(UUID.randomUUID());
    user.setDefaultAvailability(defaults);

    Date monday = DateUtility.parseDate("2025-01-13");   // a Monday

    AvailabilityResponse response = AvailabilityUtility.availabilityResponseBuilder(
            user, new ArrayList<>(List.of(user)), List.of(), List.of(),
            monday, AvailabilityWindowType.DAY);

    assertEquals(1, response.getAvailabilityBuckets().size());
    assertEquals("0900", response.getAvailabilityBuckets().get(0).getStartTime());  // ❌ gets "1000"
}
```

No Spring, no database, no mocks, milliseconds to run. **The reason this bug survived is
that the README's sample data sets every weekday to identical hours** — uniform fixtures
cannot detect ordering errors. Make every test value distinguishable and the mix-up has
nowhere to hide.

### What to test, in priority order

| Layer | Tool | What it proves |
|---|---|---|
| `AvailabilityUtility` | plain JUnit | The algorithm is right — **start here, highest value per line** |
| Services | JUnit + Mockito | Business rules (organiser-only, custom overrides default) |
| Repositories | `@DataJpaTest` + Testcontainers | Queries and mappings actually work |
| Controllers | `@WebMvcTest` + MockMvc | Status codes, JSON shape, validation |
| End to end | `@SpringBootTest` + Testcontainers | The wiring holds together |

**Testcontainers** starts a real MySQL in Docker for the test run. Use it instead of H2 —
H2 accepts SQL MySQL rejects, has different `binary(16)` handling, and different `ENUM`
semantics, so "passes on H2, fails in prod" is a real and demoralising experience.

Don't chase a coverage number. **Test the code where being wrong is expensive** — that's
`AvailabilityUtility`, and it's currently at zero.

---

## The N+1 query problem

```java
for (AudienceReq audienceReq : request.getAudienceReq()) {
    UserDetails userDetails = userService.getUserFor(audienceReq.getEmail());   // one query each
    ...
}
```

10 participants = 11 queries. Each is a network round trip; at 1 ms each that's 11 ms of
pure latency for work one query could do.

The fix is already in the codebase, unused here:

```java
List<UserDetails> users = userService.getUserFor(emails);        // findByEmailIn — ONE query
Map<String, UserDetails> byEmail = users.stream()
        .collect(Collectors.toMap(UserDetails::getEmail, u -> u));
// then validate that every requested email resolved — fixing the silent-drop bug too
```

**Watch for the general shape: a repository call inside a loop.** It's the most common
performance bug in ORM-based applications, it's invisible at 10 rows, and it's fatal at
10,000. `spring.jpa.show-sql=true` makes it obvious — turn it on and count.

The subtler version is *lazy loading* inside a loop: `for (EventAudience a : audiences)
a.getUser().getName()` fires one query per audience, with no repository call in sight. Use
`JOIN FETCH` or an `@EntityGraph` when you know you'll need the association.

---

## Timezones are not implemented

The README is upfront: *"This feature have supports for Timezone but its not implemented."*
The system assumes everyone is in IST.

The gap runs four layers deep, which is why it's expensive:

| Layer | What blocks it |
|---|---|
| Schema | `enum('IST')` — needs an `ALTER` |
| Entities | `varchar(4)` local times with no offset |
| Algorithm | `// Will add timezone support here` — the comment marks the spot |
| API | Times are wall-clock strings with no zone attached |

**The correct model** for a calendar:
1. Store instants in **UTC** (`TIMESTAMP` / `datetime` in UTC), always.
2. Store the **originating timezone** separately (`Asia/Kolkata`, an IANA name — never
   `IST`, which is ambiguous between India and Ireland and Israel).
3. Convert to local time at the **edges** — display and input parsing only.

The nasty part isn't conversion, it's **DST**. "Every Monday at 09:00 local" is not a fixed
UTC instant — it shifts twice a year. Recurring events must therefore be stored as *local
time + zone + recurrence rule* and expanded at read time, not as UTC instants. Getting this
wrong is how calendar apps end up with meetings an hour off for half the year.

**The generalisable lesson: an assumption baked into the schema, the algorithm, and the API
at once is not a "TODO", it's a rewrite.** Sometimes right. But be honest with yourself
about which assumptions are load-bearing before you bake them in — that's a design-review
question, and it costs nothing to ask early.

---

## Validation in the wrong layer

`AvailabilityPatternValidator` does business validation inside a Jackson deserializer, so
its exceptions get wrapped and surface as `500` instead of `400`
([02](02-request-lifecycle.md#jackson-deserialization--and-a-project-specific-twist)).
Its `catch (Exception e)` also swallows its own specific messages
([08](08-code-walkthrough.md#controllerdeserializeravailabilitypatternvalidatorjava)).

Meanwhile the event API has **no validation at all** — no `@Valid`, no constraints.

### The right shape

```java
@Documented
@Constraint(validatedBy = AvailabilityPatternConstraintValidator.class)
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAvailability {
    String message() default "Invalid availability pattern";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

```java
public class EventSchedulingRequest {
    @NotBlank(message = "Title is required")
    private String title;
    @NotNull @Future(message = "Event must be in the future")
    private Date eventDate;
    @Pattern(regexp = "\\d{4}") private String startTime;
    @NotEmpty private List<@Valid AudienceReq> audienceReq;
}
```

Plus `@Valid` on the controller parameter, and one handler:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult().getFieldErrors()
      .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
    return ResponseEntity.badRequest().body(errors);
}
```

Now clients get **all** field errors at once with a clean 400, instead of one vague 500 at
a time. Cross-field rules (`endTime > startTime`) go in a class-level constraint.

**Principle: validate at the boundary, in the framework's own mechanism, so everything
downstream can assume valid input.**

---

## Error handling leaks internals

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
    log.info("Exception - {}", ExceptionUtils.getStackTrace(ex));
    return new ResponseEntity<>(new ErrorResponse("An error occurred: " + ex.getMessage()),
                                HttpStatus.INTERNAL_SERVER_ERROR);
}
```

Three problems:

1. **`ex.getMessage()` goes to the client.** A `SQLIntegrityConstraintViolationException`
   message names your tables, columns, and constraints. That's free reconnaissance for an
   attacker, and it happens on the one code path you can't predict.
2. **`log.info` for a 500.** Errors must be `log.error`, or they won't appear in any
   error-rate dashboard or alert you build later.
3. **No correlation ID.** A user says "it failed at 3pm" and you have no way to find their
   request among thousands.

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
    String traceId = UUID.randomUUID().toString();
    log.error("Unhandled exception traceId={}", traceId, ex);   // ex last = full stack trace
    return ResponseEntity.status(500)
            .body(new ErrorResponse("Something went wrong. Reference: " + traceId));
}
```

**Log the detail, return the reference.** Then add the missing 403 handler for
`ActionNotAllowed` and a 409 for duplicate-key violations.

(Note `log.error("...", traceId, ex)` — passing the exception as the *last* argument, with
no `{}` for it, makes SLF4J print the stack trace. Passing it inside the format string
gives you only `toString()`. Small thing, constantly gotten wrong.)

---

## Swagger is publicly exposed

`http://your-server:8080/swagger-ui/index.html` — a complete, interactive map of every
endpoint, parameter, and model, served to anyone.

Options, in increasing order of safety:
- `springdoc.swagger-ui.enabled=false` in the prod profile (keep it in dev)
- Put it behind authentication with Spring Security
- Only expose it on an internal network/VPN

Remember: **removing the springdoc dependency entirely breaks the build**, because
`commons-lang3` reaches the project through it
([09](09-java-gaps.md#the-dependency-that-isnt-declared)). Declare `commons-lang3` first,
then you're free to remove Swagger.

---

## No observability

You cannot operate what you cannot see. Today there is no way to answer:
- Is the app alive right now?
- How many requests per second? What's the p99 latency?
- Is the connection pool exhausted?
- How many 500s in the last hour?

`spring-boot-starter-actuator` gives you most of it for a one-line dependency:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=when-authorized
```

- `/actuator/health` — liveness/readiness for load balancers and systemd
- `/actuator/metrics` — JVM memory, GC, HTTP timings, Hikari pool stats
- `/actuator/prometheus` — scrapeable metrics for Grafana

⚠️ Actuator endpoints leak a great deal (`/env` shows configuration, `/heapdump` is a
memory dump). Expose the minimum, on a separate port
(`management.server.port=8081`) that isn't routed from the internet.

Also add **structured (JSON) logging** and a request ID in the MDC, so `logs/application.log`
becomes searchable rather than a wall of text.

---

## Configuration and secrets

`application.properties` ships with placeholders — **correct**. But there's no mechanism
for real values, and `spring.profiles.active=dev` is hardcoded into the committed file, so
the JAR carries an environment decision inside it.

Fix both:

```properties
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
```

and remove `spring.profiles.active` from the base file entirely — pass it at launch.

**If a secret ever reaches git, rotate it.** Deleting the line doesn't help; it's in the
history on every clone forever. Details in
[12-deploy-and-operate.md](12-deploy-and-operate.md#configuration-per-environment).

---

## Version control hygiene

| Problem | Consequence |
|---|---|
| No `.gitignore` | `target/`, `logs/`, `.idea/` are untracked-but-unignored; someone will commit a 40 MB JAR |
| `.mvn/wrapper/` missing | `mvnw` is committed but unusable ([01](01-setup-and-first-run.md#-the-maven-wrapper-in-this-repo-is-broken)) |
| `db_schema.sql` duplicated | Two copies of one truth; only one will be updated |
| `spring.profiles.active=dev` committed | Environment choice baked into the artifact |

A minimal `.gitignore`:

```
target/
logs/
*.log
.idea/
*.iml
.vscode/
application-local.properties
```

---

## The complete list

| # | Gap | Severity | Effort |
|---|---|---|---|
| 1 | No authentication; `/user/search` hands out UUIDs | **Critical** | High |
| 2 | `GET /event/{id}` has no authorisation | **Critical** | Low |
| 3 | Double-booking not prevented (TOCTOU) | **High** | Medium |
| 4 | Weekday rotation off by one ([07](07-lld-availability-engine.md)) | **High** | Trivial |
| 5 | End-of-day availability silently dropped | **High** | Trivial |
| 6 | Touching/overlapping ranges create holes | **High** | Medium |
| 7 | No unique constraint on email → 500s on search | **High** | Trivial |
| 8 | No unique constraint on `(user_id, date)` → duplicate rows | **High** | Trivial |
| 9 | No tests | **High** | Medium |
| 10 | No migrations | **High** | Low |
| 11 | Unknown emails silently dropped | **High** | Low |
| 12 | `ActionNotAllowed` → 500 instead of 403 | Medium | Trivial |
| 13 | `SimpleDateFormat` static + not thread-safe (×2) | Medium | Trivial |
| 14 | Validation in deserializer → wrong status codes | Medium | Medium |
| 15 | No validation on the event API at all | Medium | Low |
| 16 | N+1 queries in `addEventFor` | Medium | Low |
| 17 | Error responses leak `ex.getMessage()` | Medium | Trivial |
| 18 | Swagger publicly exposed | Medium | Trivial |
| 19 | No observability / health checks | Medium | Low |
| 20 | `commons-lang3` used but undeclared | Medium | Trivial |
| 21 | Timezones unimplemented | Medium | **Very high** |
| 22 | RSVP `DECLINED` doesn't free the slot | Medium | Trivial |
| 23 | Duplicate events in availability response | Low | Trivial |
| 24 | No `.gitignore`, broken `mvnw`, duplicated schema | Low | Trivial |
| 25 | Hard deletes, no audit trail | Low | Medium |
| 26 | No pagination, versioning, rate limiting, idempotency | Low | Medium |

**Count the "trivial" rows.** Eleven of twenty-six are one-line or one-file fixes. That
ratio is completely typical, and it's the most encouraging thing in this document: most
production-readiness work is not hard, it's just *unglamorous and easy to skip*.

## Checkpoint

- [ ] Explain TOCTOU using the booking flow, and why an `if` doesn't fix it
- [ ] Write the two `ALTER TABLE` statements this schema needs
- [ ] Explain why H2 is a bad substitute for MySQL in tests
- [ ] Explain why returning `ex.getMessage()` to a client is a security issue
- [ ] Pick the three gaps you'd fix first and defend the order
