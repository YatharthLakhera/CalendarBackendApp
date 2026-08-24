# 08 — Code Walkthrough: Every Package, Every File

The goal from your brief: *understand why every line is there*. This file goes package by
package. Files already dissected elsewhere are cross-referenced rather than repeated.

```
com.calendar.app
├── CalendarAppApplication.java      the entry point
├── GlobalExceptionHandler.java      exception → HTTP status, in one place
├── controller/                      HTTP in, HTTP out
│   ├── deserializer/                JSON → Java (with validation smuggled in)
│   └── serializer/                  Java → JSON
├── services/                        business rules
├── db/
│   ├── entity/                      tables, as Java
│   ├── repository/                  queries
│   ├── converter/                   the JSON column
│   └── UserDefaultAvailability.java (an odd one out — see below)
├── models/
│   ├── api/                         request/response DTOs — the public contract
│   └── helper/                      small shared value objects
├── enums/                           domain vocabulary
└── exceptions/                      domain failures
```

---

## `CalendarAppApplication.java`

```java
@SpringBootApplication
public class CalendarAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(CalendarAppApplication.class, args);
    }
}
```

Thirteen lines that do an enormous amount. `@SpringBootApplication` is three annotations
in one:

- `@ComponentScan` — scan **this package and everything below it** for `@Component`,
  `@Service`, `@RestController`, `@Repository`, `@ControllerAdvice`, `@Configuration`.
- `@EnableAutoConfiguration` — for every JAR on the classpath, apply its opinionated
  defaults. `spring-boot-starter-web` on the classpath ⇒ start Tomcat. `mysql-connector-j`
  + a `spring.datasource.url` ⇒ build a `DataSource` and a HikariCP pool. `springdoc` ⇒
  publish Swagger UI. **You configured none of that.**
- `@Configuration` — this class may itself declare `@Bean` methods (it doesn't).

**The practical consequence of component scanning:** the class's *package location* is
load-bearing. `com.calendar.app` is the root, so everything under it is scanned. Move this
class into `com.calendar.app.boot` and every controller and service in sibling packages
silently stops being registered — you'd get 404s everywhere with no error at startup. If
you ever see "my `@Service` isn't being injected", check the package first.

---

## `controller/`

All three controllers follow the same shape: extract inputs, delegate, return. No logic.
Fully covered in [03-api-reference.md](03-api-reference.md) and
[02-request-lifecycle.md](02-request-lifecycle.md).

Two conventions worth naming:

**Field injection via `@Autowired`:**

```java
@Autowired private AvailabilityService availabilityService;
```

This works and is common in older code. Modern Spring prefers **constructor injection**:

```java
private final AvailabilityService availabilityService;

public AvailabilityController(AvailabilityService availabilityService) {
    this.availabilityService = availabilityService;
}
```

(With Lombok, `@RequiredArgsConstructor` on the class generates that for you, so it's a
one-line change.)

Three concrete reasons constructor injection wins — worth knowing because it will come up
in every code review you're ever in:
1. The field can be `final`, so it's immutable and guaranteed non-null after construction.
2. You can build the object in a unit test with `new UserController(mockService)` — no
   Spring, no reflection, no `@SpringBootTest`. Field injection forces you into a Spring
   context or reflection hacks just to set a private field.
3. A class with eight constructor parameters *looks* wrong, which pressures you to split
   it. Eight `@Autowired` fields look fine. **Constructor injection makes bad design
   visible** — that's its most underrated benefit.

**Method naming: `getUserFor`, `addEventFor`, `updateUserAvailabilityFor`.** The `...For`
suffix is used consistently across all three layers. It's an unusual convention, but it *is*
a convention, applied uniformly. Consistency beats correctness in naming: a codebase where
every method follows one odd rule is far easier to navigate than one where each file
follows a different good rule. When you contribute, match it.

### `controller/deserializer/CustomDateDeserializer.java` and `controller/serializer/CustomDateSerializer.java`

```java
private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy-MM-dd");
```

🐞 **`SimpleDateFormat` is not thread-safe, and this one is `static`** — a single instance
shared across every Tomcat request thread. It holds mutable parsing state (`Calendar`)
internally. Under concurrent load this doesn't throw a helpful exception; it produces
**wrong dates**, or occasionally a `NumberFormatException` from deep inside the JDK.

This is one of the most famous bugs in Java, it is invisible in single-threaded testing,
and it is present twice in this repo (both classes). It will not show up on your laptop.
It will show up in production, intermittently, and it will take you a week.

Three fixes, best last:
```java
// (a) new instance per call — correct, slightly wasteful
new SimpleDateFormat("yyyy-MM-dd").parse(text);

// (b) ThreadLocal<SimpleDateFormat> — correct, ugly
// (c) java.time — DateTimeFormatter IS thread-safe and immutable by design:
private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;
```

`DateUtility.parseDate` avoids the bug by creating a new formatter per call (option a).
Same repo, both patterns — the safe one and the unsafe one.

**Why these classes exist at all:** the project uses `java.util.Date`, which Jackson
serialises by default as a millisecond epoch number (`1733875200000`) or a long ISO
timestamp — neither of which is what a calendar API should return. These force
`"2024-12-11"`. Switch the model to `java.time.LocalDate` and both classes can be deleted:
`jackson-datatype-jsr310` (already on the classpath, see the dependency tree) handles it
natively.

### `controller/deserializer/AvailabilityPatternValidator.java`

```java
public class AvailabilityPatternValidator extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String availabilityStr = p.getText();
        if (StringUtils.isBlank(availabilityStr)) return null;
        try {
            for (String[] availability : AvailabilityParseUtility.parse(availabilityStr)) {
                int startTime = Integer.parseInt(availability[0]);
                int endTime   = Integer.parseInt(availability[1]);
                if (startTime >= endTime) throw new BadRequestException("Start time should be less than end time");
                if (startTime % 5 != 0 || endTime % 5 != 0) throw new BadRequestException("Time should be a multiple of 5");
            }
        } catch (NumberFormatException e) {
            throw new BadRequestException("Time should be in 24 hr format i.e. 1000");
        } catch (Exception e) {
            throw new BadRequestException("Time availability format is not valid");
        }
        return availabilityStr;
    }
}
```

The class name says "Validator"; the type it extends says "Deserializer". **It is doing
two jobs**, and that's the interesting part.

What's good: the validation itself is correct and it runs at the outermost boundary, so
bad data never reaches the service. Normalising blank → `null` in one place is also right.

What's wrong, in order of severity:

1. 🐞 **`catch (Exception e)` swallows the `BadRequestException`s thrown three lines
   above.** `BadRequestException extends RuntimeException extends Exception`, so the
   specific message *"Start time should be less than end time"* is caught by the generic
   handler and replaced with *"Time availability format is not valid"*. Every validation
   failure produces the same vague message. Order your `catch` blocks narrowest-first, and
   never catch a type broader than the ones you're deliberately throwing inside the `try`.
2. 🐞 The exceptions get wrapped by Jackson anyway, so they don't reach
   `GlobalExceptionHandler` as `BadRequestException` — see
   [02](02-request-lifecycle.md#jackson-deserialization--and-a-project-specific-twist).
   Net effect: a `500` where a `400` belongs.
3. ⚠️ `AvailabilityParseUtility.parse("1000")` returns `[["1000"]]` — a one-element array.
   Then `availability[1]` throws `ArrayIndexOutOfBoundsException`, caught by the generic
   `catch`. It's *handled*, but by accident rather than by design.
4. ⚠️ Doesn't check ranges against each other — the cause of the 🐞 hole bug in
   [07](07-lld-availability-engine.md#-inclusive-bounds-and-the-surprising-consequence).

**Where this belongs instead:** Bean Validation. Write a `@ValidAvailability` annotation
backed by a `ConstraintValidator<ValidAvailability, String>`, put it on the field, add
`@Valid` to the controller parameter. Then Spring collects *all* violations, reports them
per-field, and returns a clean `400` — for free, through machinery that already exists.
[13-exercises.md](13-exercises.md) has this as an exercise.

---

## `services/`

### `UserService`

The interesting thing here is the **access modifiers**:

```java
public  UserResponse getUserResponseFor(final UUID userId)   // controllers call this
public  UserResponse searchFor(final String email)
public  UserResponse addUserFor(UserRequest userRequest)
public  UserResponse updateUserFor(final UUID userId, UserUpdateRequest request)

protected List<UserDetails> getUserFor(final List<String> emails)   // other services call these
protected UserDetails     getUserFor(final String email)
protected UserDetails     getUserFor(final UUID userId)
protected UserDetails     createOrUpdate(UserDetails userDetails)
```

**The `public` methods return DTOs. The `protected` methods return entities.** That split
is deliberate and it's the best design decision in this file: a controller physically
cannot get its hands on a `UserDetails` entity, so it cannot accidentally serialise the
database model to JSON, and it cannot mutate a managed Hibernate entity outside a
transaction.

The javadoc says *"This function is only accessible in service package"*. That's
approximately right but worth stating precisely, because Java's `protected` is unusual:

> `protected` = **package-private + subclasses in any package**.

So `AvailabilityService` (same package) can call it — the intent. But so could a subclass
in a completely different package. If you truly want package-only, use **no modifier at
all** (package-private), which is stricter than `protected`. Ranked, narrowest first:

```
private  <  (no modifier / package-private)  <  protected  <  public
```

Most people assume `protected` is narrower than package-private. It isn't.

⚠️ The `final` on parameters (`final UUID userId`) prevents reassigning the parameter
inside the method. It's harmless and some teams like it; note it does **not** make the
object immutable, only the reference. Applied inconsistently here — some methods have it,
some don't.

Note also the **overloading** of `getUserFor` three ways (`UUID`, `String`,
`List<String>`). Fine, since the types are unambiguous. Add a `getUserFor(Object)` and
resolution becomes a puzzle.

### `AvailabilityService`

```java
@Autowired private UserService userService;
@Autowired private UserCustomAvailabilityRepository userCustomAvailabilityRepository;
@Autowired private EventSchedulerService eventSchedulerService;
```

A service depending on two other services plus a repository. That's normal and fine.
Watch for the failure mode: **circular dependencies**. If `EventSchedulerService` ever
`@Autowired`s `AvailabilityService` back (which it would need to, to check availability
before booking — see [11](11-production-gaps.md#double-booking-is-not-prevented)), Spring
will refuse to start with `The dependencies of some of the beans form a cycle`. Field
injection sometimes lets a cycle limp along; constructor injection fails it loudly. The
real fix is extracting the shared logic into a third component that both depend on.

`updateUserAvailabilityFor` is the upsert with the race condition described in
[03](03-api-reference.md#put-availabilitycustom--override-a-single-date):

```java
List<UserCustomAvailability> customAvailabilities =
        userCustomAvailabilityRepository.findByUserIdAndAvailabilityDate(userId, request.getDate());
UserCustomAvailability userCustomAvailability = new UserCustomAvailability(userId);
if (customAvailabilities != null && !customAvailabilities.isEmpty()) {
    userCustomAvailability = customAvailabilities.get(0);
}
userCustomAvailability.update(request);
userCustomAvailabilityRepository.save(userCustomAvailability);
```

Note `findBy...` returns a `List` and the code takes `.get(0)` — the repository method
*could* have returned `Optional<UserCustomAvailability>`, which would be honest about
expecting at most one. Returning `List` and taking element 0 is a way of saying "I know
there might be duplicates and I've decided not to care." With a `UNIQUE KEY` on
`(user_id, availability_date)`, `Optional` would be both correct and self-documenting.

Also: the first line loads the user (`userService.getUserFor(userId)`) purely to verify
they exist and to log them — the result is otherwise unused. That's an extra query per
call. Deliberate validation, but worth recognising as a cost.

`getAvailabilityFor` is dissected in [07](07-lld-availability-engine.md#the-service-side-what-feeds-the-engine).

### `EventSchedulerService`

```java
public EventSchedulingResponse addEventFor(UUID organiserId, EventSchedulingRequest request) {
    UserDetails organiser = userService.getUserFor(organiserId);
    EventScheduler eventScheduler = new EventScheduler(request);
    eventScheduler.addAudience(organiser, UserEventRole.ORGANISER, EventStatus.ACCEPTED);
    for (AudienceReq audienceReq : request.getAudienceReq()) {
        UserDetails userDetails = userService.getUserFor(audienceReq.getEmail());   // ← query in a loop
        eventScheduler.addAudience(userDetails, audienceReq.getRole(), EventStatus.PENDING);
    }
    eventScheduler = eventSchedulerRepository.save(eventScheduler);
    return new EventSchedulingResponse(eventScheduler);
}
```

Four things:

1. 🐞 **N+1 queries.** One `SELECT` per participant. `UserRepository.findByEmailIn` exists
   and would do it in one — `AvailabilityService` already uses it. Same team, same week,
   opposite habit.
2. 🐞 **No `@Transactional`.** N+1 separate transactions. Since only the last statement
   writes, nothing is left half-done *today* — but a method that does several repository
   calls and expects them to be one unit of work needs the annotation. Add a second write
   later without noticing, and you have a partial-failure bug.
3. 🐞 **`request.getAudienceReq()` is dereferenced without a null check.** Omit
   `audienceReq` from the JSON and you get an NPE → `500`. Nothing validates the body.
4. 🐞 **No availability check** — the product's core promise, unenforced. See
   [11](11-production-gaps.md#double-booking-is-not-prevented).

What's *good*: `eventScheduler.addAudience(...)` puts the "how do I attach a person to an
event" logic **on the entity**, not in the service. The entity maintains its own
invariants — it lazily creates the list, and constructs `new EventAudience(this, ...)` so
both sides of the bidirectional relationship are wired. That's the difference between a
domain model and an anaemic bag of getters, and it's why the service reads like the
business rule.

```java
@Transactional
public EventSchedulingResponse rescheduleEventFor(UUID userId, UUID eventId, EventSchedulingRequest request) {
    ...
    eventScheduler.update(request);
    eventScheduler.removeAllAudiences();
    eventScheduler.addAudience(organiser, UserEventRole.ORGANISER, EventStatus.ACCEPTED);
    for (AudienceReq audienceReq : request.getAudienceReq()) { ... }
    eventScheduler = eventSchedulerRepository.save(eventScheduler);
}
```

**Why this one needs `@Transactional` and the others don't.** The sequence is
delete-then-insert on `event_audience`, driven by `orphanRemoval = true`. Without a single
transaction spanning it:
- the deletes and inserts are separate units of work,
- a failure between them leaves an event with **no audience at all** — no organiser, so
  nobody can ever delete or fix it,
- and a concurrent reader could observe that empty state.

`@Transactional` makes it atomic: all of it or none of it.

⚠️ Note the import: `jakarta.transaction.Transactional`, not
`org.springframework.transaction.annotation.Transactional`. Both work; Spring's version
supports `readOnly`, `propagation`, `isolation`, and `rollbackFor`, and behaves more
predictably around rollback rules. Prefer Spring's.

⚠️ And know the one rule that catches everyone: **`@Transactional` works via a proxy, so
it only applies to calls that come in from outside the bean.** If `addEventFor` called
`this.rescheduleEventFor(...)` internally, the annotation would be **silently ignored** —
the call never leaves the object, so the proxy never sees it. No error, no warning, no
transaction. This is the single most common Spring gotcha in existence.

```java
protected EventScheduler getEventBy(UUID eventId) { ... }
protected List<EventScheduler> getEventBy(List<UserDetails> userDetails, Date fromDate, Date toDate) { ... }
```

Same `public` DTO / `protected` entity split as `UserService`. Consistent. Good.

---

## `db/entity/`

Covered in [04-db-schema-and-orm.md](04-db-schema-and-orm.md#entity-by-entity-mapping).
Two methods deserve a closer look here.

### `EventScheduler.removeAllAudiences()`

```java
public void removeAllAudiences() {
    for (EventAudience audience : new HashSet<>(this.audiences)) {
        remove(audience);
    }
}

public void remove(EventAudience audience) {
    audience.setEvent(null);      // clear the child's reference
    audiences.remove(audience);   // remove from the parent's list
}
```

The `new HashSet<>(this.audiences)` is the important part. Iterating a collection while
removing from it throws `ConcurrentModificationException` — that's true single-threaded,
despite the name. Copying first sidesteps it.

⚠️ But a `HashSet` copy is an odd choice, and it interacts badly with `EventAudience`'s
`hashCode()`:

```java
@Override public int hashCode() { return getClass().hashCode(); }
```

**Every `EventAudience` returns the same hash code.** In a `HashSet` they all collide into
one bucket, so the set degenerates to a linked list and equality checks run pairwise — and
`equals` compares `id`, which is `0` for every not-yet-persisted audience. So for a
freshly built event, all unsaved audiences are "equal" to each other, and
`new HashSet<>(audiences)` collapses them into **one element**.

Does it break? Not in the current flow — during reschedule the audiences are loaded from
the DB with distinct non-zero `id`s, so the set holds them all. But the method is one
refactor away from silently dropping people from a meeting. `new ArrayList<>(this.audiences)`
would be simpler, faster, and immune. (Why `hashCode` is written that way at all is
explained in [09](09-java-gaps.md#why-that-strange-equalshashcode-exists) — it's not a
mistake, it's a trade-off.)

Also note `audiences.remove(audience)` calls `equals`, which compares by `id` — so it too
depends on entities having distinct ids.

### `EventScheduler.isOrganisedBy(UserDetails)`

```java
public boolean isOrganisedBy(UserDetails user) {
    for (EventAudience audience : this.audiences) {
        if (user.getUserId().equals(audience.getUser().getUserId())
                && audience.getRole() == UserEventRole.ORGANISER) {
            return true;
        }
    }
    return false;
}
```

**The authorisation rule lives on the entity.** Every caller — delete, reschedule, and the
availability response filter — asks the same object the same question, so the rule cannot
drift between call sites. This is exactly right, and it's the pattern that
`GET /event/{eventId}` forgot to use.

Note `==` on the enum (correct — enum constants are singletons, so reference equality is
identity) versus `.equals()` on the UUID (correct — `UUID` is a value object; `==` would
compare references and fail). Getting these two right in one expression is a good sign.

⚠️ `this.audiences` is a `LAZY` collection. Touching it outside an open persistence context
throws `LazyInitializationException`. It works here because the callers are services
invoked within a request that still has an open `EntityManager` (Spring Boot's
`spring.jpa.open-in-view` defaults to `true`). That default is convenient and widely
criticised — it keeps a DB connection held for the entire request, including JSON
serialisation. Turning it off is a common production hardening step, and it would break
this code until the queries were made to fetch what they need explicitly.

---

## `db/repository/`

Four interfaces, zero implementations. Two query styles:

**Derived from the method name:**
```java
Optional<UserDetails> findByEmail(String email);
List<UserDetails> findByEmailIn(List<String> emails);
List<UserCustomAvailability> findByUserIdAndAvailabilityDate(UUID userId, Date availabilityDate);
List<EventAudience> findByEventAndUser(EventScheduler event, UserDetails user);
```

Spring Data parses `findBy` + `Email` + `In` and generates JPQL. Note
`findByEventAndUser` takes **entities**, not IDs — Spring Data unwraps them to their
primary keys.

**Explicit JPQL:**
```java
@Query("SELECT e FROM EventScheduler e JOIN e.audiences aud WHERE aud.user.userId IN :userIds AND e.eventDate BETWEEN :startDate AND :endDate")
List<EventScheduler> findByParticipantUserIdsAndDateRange(@Param("userIds") List<UUID> userIds, ...);
```

Used when name-derivation can't express the query (here: a join to a child collection plus
a range). **This is JPQL, not SQL** — `EventScheduler` is the *entity* name and
`e.audiences` navigates a *Java field*, not a column. Hibernate translates it into SQL
against `event_scheduler` / `event_audience`.

`@Param("userIds")` binds the name used in the query. It's required unless you compile
with `-parameters`; be explicit and never think about it again.

Missing-`DISTINCT` duplicates: [07](07-lld-availability-engine.md#the-service-side-what-feeds-the-engine).

⚠️ `@Repository` is on `UserRepository` but not on the other three. It makes no functional
difference — Spring Data registers repository interfaces regardless — so this is
inconsistency, not a bug. (`@Repository` matters on hand-written DAO classes, where it
enables exception translation.)

---

## `db/UserDefaultAvailability.java` — the odd one out

This class lives in `db/` but is **not an entity**. It's simultaneously:

- the JSON stored in `user_details.default_availability` (via the converter)
- the request body of `PUT /availability/defaults`
- the response body of `GET /availability/defaults`

Three roles, one class. See [06-hld.md](06-hld.md#where-the-layering-genuinely-leaks).

```java
@JsonIgnore
public List<String> getWeeksAvailability() {
    List<String> weeksAvailability = new ArrayList<>();
    weeksAvailability.add(StringUtils.isBlank(this.monday) ? "" : this.monday);
    ... // ×7
    return weeksAvailability;
}
```

`@JsonIgnore` is **essential** here, and for a non-obvious reason: Jackson treats any
`getXxx()` as a property. Without it, every response and every stored JSON blob would gain
a bogus `"weeksAvailability": ["...", ...]` array — and on the way back in, Jackson would
try to *set* it and fail. **Any computed getter on a serialised class needs `@JsonIgnore`.**
Learn this reflex; it's a very common source of surprise fields in API responses.

Seven near-identical lines is the kind of thing a `List<String>` field or a
`Map<DayOfWeek, String>` would collapse — but then the JSON shape changes, and the JSON
shape is the DB format *and* the API contract. **That's the cost of the three-roles
design, made concrete:** you can't refactor the Java without a data migration and an API
break.

---

## `models/api/` — the DTO layer

```
UserRequest extends UserUpdateRequest     name + email
UserResponse extends UserRequest          name + email + id
```

⚠️ **Inheriting DTOs is a trap and this is a textbook example.** It reads as "a response is
a request plus an id", which is true *today* by coincidence. The moment you want a field
on the response but not the request (`createdAt`, `lastLogin`) or vice versa (`password`),
the hierarchy fights you — and every change to the parent silently changes the child in
both directions.

There's already a symptom: `UserResponse` is `@Data` extending `@Data`. Lombok's generated
`equals`/`hashCode` **do not consider superclass fields** unless you add
`@EqualsAndHashCode(callSuper = true)`. So two `UserResponse` objects with the same `id`
but different names compare as equal. Lombok warns about this at compile time; the warning
is being ignored.

**Prefer composition or flat DTOs.** A little duplication between request and response
types is not a problem worth solving with inheritance — see
[10-design-principles.md](10-design-principles.md#l--liskov-substitution-and-why-dto-inheritance-hurts).

`EventSchedulingRequest` has a constructor taking an `EventScheduler` **entity** — i.e. it
can be built *from* the DB model even though it's a request type. It's unused. Dead code
that creates a dependency from the API layer to the entity layer. Delete it.

`AvailabilityResponse` has `public` fields (not `private` + Lombok getters) — inconsistent
with every other class here, and it means the `@Data` getters are redundant. Harmless,
but it's the kind of thing a linter should catch.

---

## `models/helper/`

`User`, `AudienceReq`, `AudienceRes`, `AvailabilityBucket` — small value objects shared
between layers.

`User` is worth pointing at: it exposes `userId`, `email`, `name` — **and nothing else**.
It's what `AudienceRes` embeds so that the event API can show attendees without leaking
`defaultAvailability` (which `UserDetails` carries). **A DTO is not just a serialisation
convenience; it is an access-control boundary.** Every field you *don't* put in a DTO is a
field that can never accidentally leak.

`AvailabilityBucket` has two constructors — one from `("1000", "1100")` strings, one from
`(1000, 1100)` ints — with `StringUtils.leftPad(..., 4, '0')` in the int version so that
`900` becomes `"0900"`. That padding is load-bearing: it's what keeps the `varchar(4)`
lexicographic-vs-numeric ordering assumption from
[04](04-db-schema-and-orm.md#design-decision-2-time-as-varchar4) true.

(There's a stray double semicolon `;;` on that line. Harmless — Java allows empty
statements — but it's what a linter is for.)

---

## `enums/`

```java
public enum AvailabilityWindowType {
    DAY(1), WEEK(7);
    private int days;
    public int getDays() { return days; }
}
```

**An enum with data and behaviour, not just names.** This is the single cleanest piece of
design in the project: `AvailabilityUtility` never says `if (windowType == DAY) days = 1;`
— it asks `windowType.getDays()`. Adding `FORTNIGHT(14)` requires **zero changes** to the
algorithm. That's the Open/Closed Principle, achieved with one field.

⚠️ `private int days` should be `private final int days` — an enum's state ought to be
immutable, and `final` here is free.

`TimeZone` has exactly one constant, `IST`, mirroring `enum('IST')` in the schema. Note it
shadows `java.util.TimeZone`, so any file needing the JDK class must fully qualify it. A
name like `SupportedTimeZone` would avoid that.

---

## `exceptions/`

```
RuntimeException
├── NotFoundException(String)        → 404
│   ├── UserNotFound()               → "User Not Found"
│   └── EventNotFound()              → "Event Not Found"
├── BadRequestException(String)      → 400
└── ActionNotAllowed()               🐞 → 500, should be 403
```

The hierarchy is the good idea: `GlobalExceptionHandler` maps the **base** type, so adding
`AvailabilityNotFound extends NotFoundException` automatically returns 404 with no handler
change. Open/Closed again.

`ActionNotAllowed` is the counter-example — it sits outside the hierarchy, so it gets no
mapping, so it becomes a 500. **One missing `extends` turns an authorisation failure into
a fake outage.** The fix is one word:

```java
public class ActionNotAllowed extends ForbiddenException { ... }   // with a 403 handler
```

All of these extend `RuntimeException` (unchecked) rather than `Exception` (checked). That
is the right call for a web app: a checked exception would force `throws` declarations up
through every layer, and there is nothing a controller could meaningfully *do* about
"user not found" except let the `@ControllerAdvice` translate it. Checked exceptions are
for recoverable, expected conditions in a library API; unchecked for "this request is
over".

---

## `utils/`

All four utility classes share the same construction:

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DateUtility { ... static methods only ... }
```

Lombok generates a private no-arg constructor, so `new DateUtility()` won't compile from
outside. That's the standard "this is a namespace, not a type" idiom. The class should
also be `final` to block subclassing, but a private constructor already prevents it in
practice.

**Why are these `static` utilities rather than Spring `@Component` beans?** Because they
are **pure functions**: no state, no I/O, no dependencies. Making them beans would buy
nothing and cost injection ceremony everywhere.

The trade-off is testability of the *callers*: you cannot mock a static method without
PowerMock/`mockito-inline`. That is fine here — you'd never *want* to mock
`AvailabilityUtility`, you'd want to test it directly with real inputs, which is exactly
what its purity makes easy. **Rule of thumb: pure computation → static utility; anything
that talks to the outside world → injected bean.**

`AvailabilityUtility` is [07](07-lld-availability-engine.md).
`AvailabilityParseUtility` is the tiny string splitter it depends on — note it does no
validation at all, which is why `parse("1000")` returns a ragged array and the caller has
to defend.

---

## `GlobalExceptionHandler`

Covered in [02](02-request-lifecycle.md#step-8--exceptions-become-http-responses). One
more detail: the nested `ErrorResponse` class is `public static` inside the handler. It
*is* part of the API contract — every error body in the system has this shape — so it
would be better in `models/api/` where a client-facing type belongs. Minor, but if you're
generating a client SDK from the OpenAPI spec, where a type lives affects what gets
generated.

---

## Things that aren't there, and should be

| Missing | Why it matters |
|---|---|
| Tests (there is one, `contextLoads`, asserting nothing) | [11](11-production-gaps.md#there-are-no-tests) |
| `.gitignore` | `target/`, `logs/`, IDE files, local config are all untracked-but-unignored |
| `.mvn/wrapper/` | `mvnw` is committed but unusable |
| `application-dev.properties` | `spring.profiles.active=dev` refers to a file that doesn't exist |
| Migrations (Flyway/Liquibase) | `db_schema.sql` is applied by hand, and exists in two copies |
| `commons-lang3` in `pom.xml` | Used in **8 files**, declared in none — see [09](09-java-gaps.md#the-dependency-that-isnt-declared) |
| Any use of `commons-io` | Declared in `pom.xml`, imported nowhere |
| Health/metrics endpoint | [12](12-deploy-and-operate.md#health-checks-and-why-you-need-them) |

## Checkpoint

- [ ] Explain why moving `CalendarAppApplication` to another package breaks everything
- [ ] Explain the `public`-returns-DTO / `protected`-returns-entity split in `UserService`
- [ ] Explain why `rescheduleEventFor` needs `@Transactional` and `addEventFor` (mostly) doesn't
- [ ] Find the two thread-unsafe `SimpleDateFormat` fields
- [ ] Explain why `getWeeksAvailability()` needs `@JsonIgnore`
