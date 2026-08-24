# 02 — What Actually Happens Between `curl` and MySQL

You know that a request hits a `@RestController` and eventually a row appears in MySQL.
This file is about the machinery in between, because **most production bugs live in that
machinery**, not in your business logic.

Trace one real request from this project all the way down.

```bash
curl -X POST http://localhost:8080/user \
  -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com"}'
```

## Step 1 — The socket and the thread

`server.port=8080` told the embedded Tomcat inside your JAR to `bind()` and `listen()`
on TCP port 8080. Tomcat accepts your connection and hands it to a **worker thread from
a thread pool** (default max 200 in Spring Boot).

Three consequences you must internalise:

1. **Your code runs concurrently.** Two users hitting `POST /event` at the same instant
   run `EventSchedulerService.addEventFor` on two threads simultaneously. Any shared
   mutable state is a race condition. (In this project, services hold no mutable state —
   they only hold injected singleton dependencies — so they're safe. That's not an
   accident; it's why stateless services are the default in Spring.)
2. **A slow request occupies a thread.** 200 concurrent slow requests and the 201st user
   waits. This is the classic "the site is down" that is actually "the thread pool is
   exhausted, usually because the DB is slow."
3. **Anything you store in a thread-local leaks between requests** unless cleaned up.
   Not an issue here, but it's why `ThreadLocal` has a scary reputation.

## Step 2 — DispatcherServlet routes the request

Spring MVC's `DispatcherServlet` is the single servlet that receives everything. It looks
at method (`POST`) + path (`/user`) and finds the handler method registered by
`@PostMapping("/user")` on `UserController.addUserFor`.

If no handler matches, you get a 404 that **never reaches your code** — which is why a
typo'd URL doesn't hit your `GlobalExceptionHandler` in the way you might expect.

## Step 3 — Argument resolution (this is where the interesting failures are)

Spring must turn an HTTP request into `addUserFor(UserRequest userRequest)`. Each
parameter annotation picks a different resolver:

| Annotation | Source | Used in this project |
|---|---|---|
| `@RequestBody` | JSON body, via Jackson | `UserController.addUserFor` |
| `@RequestHeader("userId")` | HTTP header | Every authenticated-ish endpoint |
| `@PathVariable("eventId")` | URL path segment | `EventSchedulerController` |
| `@RequestParam("email")` | Query string | `UserController.searchUserFor` |

### Type conversion happens here, and it can fail before your code runs

```java
public UserResponse getUserFor(@RequestHeader("userId") UUID userId)
```

The header arrives as a `String`. Spring converts it to `UUID` using its `ConversionService`.
Send `-H 'userId: banana'` and conversion throws — **before a single line of
`UserController` executes.** Try it:

```bash
curl -i http://localhost:8080/user -H 'userId: banana'
```

Same for `@PathVariable("windowType") AvailabilityWindowType windowType`: Spring converts
the path string to the enum by exact name match. `/availability/2024-12-11/WEEK` works;
`/availability/2024-12-11/week` does not.

**Why this matters:** a whole class of 400/500 responses in a Spring app originate in
argument resolution, and you will waste an afternoon looking for the bug in your service
if you don't know this layer exists.

### Jackson deserialization — and a project-specific twist

`@RequestBody UserRequest` means Jackson's `ObjectMapper` maps JSON → `UserRequest`.
Jackson needs a no-arg constructor (that's what Lombok's `@NoArgsConstructor` is for
on these classes — see [09-java-gaps.md](09-java-gaps.md)) and setters.

This project does something unusual: it **runs validation inside custom Jackson
deserializers**.

```java
// UserDefaultAvailability.java
@JsonDeserialize(using = AvailabilityPatternValidator.class)
private String monday;
```

`AvailabilityPatternValidator` extends `JsonDeserializer<String>` and, while converting
the string, checks that `"1000:1100;1300:1800"` parses, that start < end, and that times
are multiples of 5 — throwing `BadRequestException` if not.

🐞 **This has a consequence you should trace yourself.** Jackson's `BeanDeserializer`
catches exceptions thrown by field deserializers and wraps them (`_wrapAndThrow`) in a
`JsonMappingException` — a `RuntimeException` thrown inside a deserializer generally does
*not* propagate out of `ObjectMapper` unchanged. So the `BadRequestException` never
arrives at `GlobalExceptionHandler`'s `@ExceptionHandler(BadRequestException.class)` in
the form you'd expect. Send a bad pattern and see what status code and message you
actually get:

```bash
curl -i -X PUT http://localhost:8080/availability/defaults \
  -H 'userId: <your-uuid>' -H 'Content-Type: application/json' \
  -d '{"timeZone":"IST","monday":"1100:1000"}'
```

Record what you get. Then read [11-production-gaps.md](11-production-gaps.md#validation-in-the-wrong-layer)
for what the alternative design looks like (a `@Pattern`/custom `ConstraintValidator` plus
`@Valid`, which plugs into Spring's `MethodArgumentNotValidException` and produces a clean
400 with field-level errors).

### Bean Validation — only where it's asked for

```java
public UserResponse addUserFor(@Valid @RequestBody UserRequest userRequest)
```

`@Valid` triggers Hibernate Validator against the `@NotNull` / `@Email` constraints on
`UserRequest`. **Without `@Valid`, those annotations do absolutely nothing.** They are
inert metadata.

Now grep this project for `@Valid`:

```bash
grep -rn "@Valid" src/main/java
```

Two hits, both in `UserController`. `AvailabilityController` and
`EventSchedulerController` have none. That's why an event can be created with a `null`
title, no audience, or an `endTime` before its `startTime`.

## Step 4 — Controller → Service → Repository

```java
// UserController
return userService.addUserFor(userRequest);
```

The controller does nothing but delegate. That is intentional and is the single most
important structural convention in this codebase — [06-hld.md](06-hld.md) explains why.

## Step 5 — Repository → SQL

`UserRepository extends JpaRepository<UserDetails, UUID>`. You wrote **no implementation**.
At startup, Spring Data JPA creates a **dynamic proxy** implementing that interface, and
for each method:

- `save(...)`, `findById(...)` etc. come from `SimpleJpaRepository`
- `findByEmail(String)` is parsed **from the method name** into a JPQL query
- `findByParticipantUserIdsAndDateRange(...)` uses the explicit `@Query` above it

Method-name derivation is why `findByEmailIn(List<String>)` works: `In` is a keyword
meaning `WHERE email IN (?)`. Rename it to `findByEmailsIn` and the app **fails at
startup**, not at call time, because Spring Data validates every derived query against
the entity when it builds the proxy. Fail-fast at startup is a feature, not a bug: a
misconfigured app that refuses to boot is far safer than one that boots and breaks at 3am.

## Step 6 — Hibernate, the persistence context, and the flush

This is the part that most surprises people coming from raw JDBC.

`userRepository.save(userDetails)` does **not** immediately run an `INSERT`. It attaches
the object to a **persistence context** (a "session", roughly a unit of work). Hibernate
issues the SQL when it *flushes* — at transaction commit, or before a query that might be
affected by pending changes.

Two big implications visible in this project:

**(a) Managed entities auto-save without you calling `save`.**

```java
// AvailabilityService.updateUserDefaultAvailabilityFor
UserDetails userDetails = userService.getUserFor(userId);   // loaded, now managed
userDetails.setDefaultAvailability(defaultAvailability);    // mutation tracked
userService.createOrUpdate(userDetails);                    // explicit save
```

Inside a transaction, that explicit `createOrUpdate` would be redundant — Hibernate's
**dirty checking** compares the entity to its loaded snapshot at flush time and emits the
`UPDATE` by itself. Calling `save()` anyway is harmless and clearer for readers, which is
why it's a defensible choice here. But you must know dirty checking exists, or you will
one day be baffled by an `UPDATE` you never asked for.

**(b) Where the transaction boundary is decides everything.**

Grep for transactions:

```bash
grep -rn "@Transactional" src/main/java
```

Exactly one hit: `EventSchedulerService.rescheduleEventFor`. Everything else runs with
whatever transaction Spring Data opens *around a single repository call* and commits
immediately after.

That means `addEventFor` — which loads N users and then saves one event — is **N+1
separate transactions**, not one. If the save fails, the loads already committed (they're
reads, so no harm here), but the general pattern is dangerous: multi-step writes without
a transaction leave partial state behind on failure.

`rescheduleEventFor` needed `@Transactional` because it does
`update → removeAllAudiences → re-add audiences → save` and relies on
`orphanRemoval = true` deleting the old `event_audience` rows *and* inserting new ones as
one atomic unit. Without a transaction spanning all of it, you can end up with an event
that has no audience at all. See
[08-code-walkthrough.md](08-code-walkthrough.md#eventschedulerservice) for the details.

## Step 7 — The response goes back through Jackson

The service returns `UserResponse`. Jackson serialises it to JSON using **getters**, not
fields. Two project-specific consequences:

```java
// AvailabilityBucket
@JsonIgnore private int startTimeValue;
```

`startTimeValue` exists only for the algorithm's internal comparisons, so `@JsonIgnore`
keeps it out of the API contract. This is the right instinct: **internal computation
fields should not leak into your public API**, because every field you emit is a field
someone will depend on.

```java
// CustomAvailabilityModel
private boolean isAvailable;
```

Lombok generates `isAvailable()`, and Jackson strips the `is` prefix for booleans — so
the JSON property is **`available`**, not `isAvailable`. Check the README's sample
request: it sends `"available": true`. Send `"isAvailable": true` and it is silently
ignored, defaulting to `false`. That is a genuinely nasty, genuinely common bug. More in
[09-java-gaps.md](09-java-gaps.md#the-isavailable-trap).

## Step 8 — Exceptions become HTTP responses

`GlobalExceptionHandler` is a `@ControllerAdvice`: a class whose `@ExceptionHandler`
methods apply to **every** controller. Without it, an uncaught exception yields Spring's
default error page/JSON blob with a stack trace shape you don't control.

The mapping here:

| Exception | Status |
|---|---|
| `NotFoundException` (and subclasses `UserNotFound`, `EventNotFound`) | 404 |
| `BadRequestException` | 400 |
| `Exception` (everything else) | 500 |

🐞 Look at `ActionNotAllowed`:

```java
public class ActionNotAllowed extends RuntimeException {
```

It extends `RuntimeException`, **not** `NotFoundException` or `BadRequestException`. So
when a non-organiser tries to delete someone else's event — an authorisation failure that
should be **403 Forbidden** — it falls through to the generic handler and returns **500
Internal Server Error**.

Why that's worse than cosmetic:

- Clients retry 5xx (it means "server broke, try again"); they don't retry 403.
- Your monitoring alerts on 5xx rate. This turns a *user error* into a *fake outage*, and
  after enough false alarms people stop trusting the alert.
- The response body becomes `"An error occurred: Unable to perform the action"` —
  concatenating the exception message into a 500 body.

⚠️ And that last point generalises: `handleGenericException` puts `ex.getMessage()` into
the response for **any** unexpected exception. A `SQLIntegrityConstraintViolationException`
message can contain table names, column names, and constraint names. That's information
disclosure — an attacker learns your schema from your error messages. Production rule:
**log the detail, return a generic message plus a correlation ID.**

Note also `log.info(...)` is used for all three handlers, including the 500 case. Errors
should be `log.error`, or they won't show up in whatever error-rate dashboard you build
later.

## The whole picture

```
curl
 │
 ▼  TCP :8080
Tomcat (thread from pool)
 │
 ▼
DispatcherServlet ──── no route? ──▶ 404 (never reaches your code)
 │
 ▼
Argument resolution:  @RequestHeader → UUID conversion  ─┐
                      @PathVariable  → enum conversion   ├─▶ failures here → 400/500
                      @RequestBody   → Jackson + custom  │    before your code runs
                                       deserializers    ─┘
 │
 ▼  @Valid (only on UserController)
Controller  ── delegates, nothing else ──▶ Service (business logic)
                                             │
                                             ▼
                                          Repository (Spring Data proxy)
                                             │
                                             ▼
                                          Hibernate: persistence context,
                                          dirty checking, flush at commit
                                             │
                                             ▼
                                          HikariCP connection pool ──▶ MySQL
 │
 ▼
Return object ──▶ Jackson serialisation (getters, @JsonIgnore, is-prefix stripping)
 │
 ▼
Exception thrown anywhere ──▶ @ControllerAdvice GlobalExceptionHandler ──▶ status + body
```

## Checkpoint

- [ ] Send `userId: banana` and explain the response without looking at the service code
- [ ] Explain why `@NotNull` on `EventSchedulingRequest` would do nothing today
- [ ] Explain why deleting someone else's event returns 500 instead of 403
- [ ] Explain when Hibernate actually issues the `INSERT` for `save()`
