# 10 — Design Principles, Using This Codebase

Your brief says you don't yet know "code best practices like open-closed, and when to use
them." The "when to use them" is the hard half — principles are easy to recite and easy to
over-apply.

**Every example below is a real line from this repo.** Where the project follows a
principle, we say why it paid off. Where it doesn't, we ask whether it should have.

---

## The meta-principle: abstraction has a cost

Before any of the letters in SOLID: **every abstraction you add is a bet.** You're paying
indirection, more files, and more concepts *now* against a change you predict *later*.

- Guess right → the change is a one-line addition.
- Guess wrong → you've made the code harder to read forever, for nothing.

The industry name for guessing wrong is **YAGNI** ("You Aren't Gonna Need It"), and it
causes at least as much damage as under-abstraction — arguably more, because
over-abstraction is much harder to undo.

The practical rule most teams converge on is **Rule of Three**: write it concretely once.
Write it concretely twice. On the *third* time, you've seen enough variation to know what
the abstraction should look like — now build it.

So when reading this file, resist "this should have an interface." Ask instead: *what
change is coming, and does this design make that change cheap?*

---

## S — Single Responsibility

> A class should have one reason to change.

The useful reading is not "a class should do one thing" (too vague to apply) but **"if two
different people, for two different reasons, would edit the same class, it has two
responsibilities."**

### ✅ Where this project gets it right

`AvailabilityUtility` computes intersections. `AvailabilityService` fetches data and calls
it. `AvailabilityController` handles HTTP.

Three reasons to change, three files:
- The API adds a query parameter → **controller** only.
- We need a new query for custom availability → **service** only.
- The intersection rule changes → **utility** only.

Concretely: the entire matrix algorithm could be replaced with the difference-array version
from [07](07-lld-availability-engine.md#complexity-and-the-version-youd-write-next) and
**neither the service nor the controller would change by one character.** That's SRP
paying rent.

### ⚠️ Where it doesn't

`UserDefaultAvailability` is (1) the JSON storage format, (2) the API request body, (3) the
API response body, and (4) a validation host via `@JsonDeserialize`. Four reasons to
change, one class.

The cost is concrete: you cannot rename `monday` to `mondayHours` for clarity, because that
would change the API contract *and* orphan every existing row in the database. Four roles
means every change must satisfy four constraints at once.

`AvailabilityPatternValidator` also has two: it's a Jackson deserializer *and* a business
validator. That collision is what produces the wrong HTTP status
([08](08-code-walkthrough.md#controllerdeserializeravailabilitypatternvalidatorjava)).

### 🤔 The judgement call

Is `EventSchedulerService` doing too much? It creates, reschedules, deletes, and handles
RSVPs — four operations.

**No.** They all change for the same reason: *the rules of event scheduling changed*. One
person owns them. Splitting into `EventCreationService`, `EventDeletionService`,
`EventRsvpService` would be over-application — you'd get more files, more injection, and
zero flexibility.

**SRP is about reasons to change, not counting methods.** People get this wrong constantly
and end up with a class per method.

---

## O — Open/Closed

> Open for extension, closed for modification. Adding a capability shouldn't mean editing
> existing, working, tested code.

### ✅ Example 1: `AvailabilityWindowType`

```java
public enum AvailabilityWindowType {
    DAY(1), WEEK(7);
    private int days;
    public int getDays() { return days; }
}
```

The algorithm never asks *which* window it is:

```java
for (int day = 0; day < windowType.getDays(); day++) { ... }
int[][] availabilityMatrix = new int[windowType.getDays()][AVAILABILITY_MATRIX_LENGTH];
```

Adding `FORTNIGHT(14)` is **one line in one enum**. Zero changes to
`AvailabilityUtility`, `AvailabilityService`, or `AvailabilityController` — Spring even
converts the new path variable automatically.

Compare the version this could have been:

```java
int days;
if (windowType == DAY) days = 1;
else if (windowType == WEEK) days = 7;      // ← and now find every other place that does this
```

`FORTNIGHT` would mean hunting down every `if` chain in the codebase, and the compiler
would help you with **none** of them. **The tell for an OCP violation is a `switch` or
`if/else` on a type code that appears in more than one place.**

Note how small the winning move was: putting data *on* the enum instead of *next to* it.
OCP doesn't require interfaces and factories.

### ✅ Example 2: the exception hierarchy

```java
@ExceptionHandler(NotFoundException.class)
public ResponseEntity<ErrorResponse> handle(NotFoundException ex) { ... 404 ... }
```

The handler binds the **base** type. `UserNotFound` and `EventNotFound` extend it, so both
map to 404 with no handler change. Add `AvailabilityNotFound extends NotFoundException`
tomorrow — it returns 404 with zero edits to `GlobalExceptionHandler`.

### 🐞 The counter-example, in the same package

```java
public class ActionNotAllowed extends RuntimeException { ... }
```

It sits **outside** the hierarchy, so it inherits no mapping and falls to the generic 500
handler. Every other exception in the package extends a mapped base and gets the right
status for free; this one had to be modified-not-extended and nobody did it.

**One missing `extends` turns an authorisation failure into a fake outage** — and it
demonstrates the flip side of OCP: when a design *is* open for extension, forgetting to
extend it correctly fails silently rather than loudly.

### 🤔 When NOT to apply it

Should `AvailabilityUtility` be an interface with pluggable strategies, so you can swap
intersection algorithms?

**No.** There is one algorithm. There has never been a second. An
`AvailabilityCalculator` interface with one implementation is pure cost: an extra file, an
extra indirection, and a reader who has to check whether there are other implementations
(there aren't). Build it when the second algorithm actually arrives.

**OCP is worth paying for on the axis where change actually happens.** Here, "how many
days in a window" changes; "how do we intersect intervals" doesn't. The project put the
flexibility in the right place, which is a better outcome than putting it everywhere.

---

## L — Liskov Substitution, and why DTO inheritance hurts

> A subclass must be usable anywhere its parent is, without surprising anyone.

### ⚠️ `UserResponse extends UserRequest extends UserUpdateRequest`

```java
class UserUpdateRequest { String name; }
class UserRequest extends UserUpdateRequest { String email; }
class UserResponse extends UserRequest { UUID id; }
```

This reads as "a response is a request plus an id." It's technically substitutable, so it
doesn't violate Liskov outright — but it's the wrong tool, and here's exactly why:

**1. The hierarchy encodes a coincidence.** Request and response happen to overlap *today*.
Add `createdAt` to the response and you either put it on the parent (where it pollutes
the request, and clients can now *send* a `createdAt`) or you break the "response = request
+ id" story that justified the inheritance.

**2. Inheritance is bidirectional coupling.** Adding a field to `UserUpdateRequest` silently
changes what `POST /user` accepts *and* what every response emits. One edit, three API
changes, no compiler warning.

**3. It creates a live Lombok bug.** `@Data` generates `equals`/`hashCode` **from that
class's own fields only** unless you add `@EqualsAndHashCode(callSuper = true)`. Nobody
did. So:

```java
new UserResponse(ashaWithNameA).equals(new UserResponse(ashaWithNameB))  // → true
```

Two responses with the same `id` but different names are "equal". Lombok emits a compile
warning about exactly this and it's being ignored.

**Prefer flat DTOs or composition:**

```java
public class UserResponse {
    private UUID id;
    private String name;
    private String email;
}
```

Yes, `name` and `email` are repeated across three classes. **That duplication is not a
problem worth solving with inheritance.** These types serve different consumers and will
diverge; a DTO's job is to be a stable, explicit contract, and inheritance makes it
implicit.

**The general rule: use inheritance for behaviour that genuinely substitutes (the exception
hierarchy above is a perfect case). Use composition or duplication for data shapes.**

---

## I — Interface Segregation

> Don't force a client to depend on methods it doesn't use.

### ✅ Visible in the repositories

```java
public interface UserRepository extends JpaRepository<UserDetails, UUID> {
    Optional<UserDetails> findByEmail(String email);
    List<UserDetails> findByEmailIn(List<String> emails);
}
```

Each repository declares exactly the queries its callers need. There is no
`GenericRepository` with thirty methods that everyone inherits.

### ✅ And in the service access modifiers

```java
public    UserResponse getUserResponseFor(UUID userId);   // for controllers
protected UserDetails  getUserFor(UUID userId);           // for sibling services
```

The controller's *view* of `UserService` contains only DTO-returning methods. It can't
reach the entity layer even by accident. **Access modifiers are interface segregation
without needing a separate interface** — a cheap technique most people never think of.

### 🤔 Should services have interfaces?

You'll see `UserService` + `UserServiceImpl` in a lot of Java code. Should this project do
that?

**No.** That pattern is cargo-culted from an era when mocking frameworks needed interfaces
and Spring AOP required JDK dynamic proxies. Neither is true now: Mockito mocks concrete
classes fine, and Spring uses CGLIB when there's no interface.

An interface with exactly one implementation is not an abstraction — it's a second file to
keep in sync, and a "go to definition" that lands you somewhere useless. Add the interface
when there is a genuine second implementation or a genuine external contract.

---

## D — Dependency Inversion

> Depend on abstractions, not concretions.

### ✅ The repositories are the textbook case

```java
@Autowired private UserRepository userRepository;
```

`UserService` depends on an **interface**. The implementation is generated at runtime by
Spring Data. The service has never heard of Hibernate, JDBC, or MySQL — swap to PostgreSQL
by changing `pom.xml` and the connection URL, with zero service changes.

That's real dependency inversion, and you got it for free by using Spring Data.

### ⚠️ Services depend on concrete services

```java
@Autowired private UserService userService;
@Autowired private EventSchedulerService eventSchedulerService;
```

Concrete classes, not interfaces. **This is fine** — see "should services have interfaces?"
above. The important discipline isn't interfaces; it's that dependencies point **downward
and never cycle** ([06-hld.md](06-hld.md#the-rule-that-makes-this-work)). The moment
`EventSchedulerService` needs `AvailabilityService` (to check availability before booking),
you get a cycle, Spring refuses to start, and the right fix is a third component both
depend on — not an interface.

---

## Beyond SOLID: the principles that actually come up daily

### DRY — and its overuse

> Don't Repeat Yourself. But *what* you shouldn't repeat is **knowledge**, not
> **characters**.

✅ **Good DRY here:** the organiser check exists once, on the entity:

```java
public boolean isOrganisedBy(UserDetails user) { ... }
```

Three callers (delete, reschedule, availability filter) ask the same object. The rule
cannot drift between call sites. *That's* knowledge deduplicated.

⚠️ **Missing DRY:** the audience-building loop is copy-pasted between `addEventFor` and
`rescheduleEventFor` — same seven lines, twice. Fix one, forget the other, and creating vs.
rescheduling behave differently.

❌ **DRY you should NOT apply:** the seven near-identical lines in `getWeeksAvailability()`.
Collapsing them into a `Map<DayOfWeek, String>` would change the JSON shape, which is both
the API contract and the database format. **Duplication that is cheaper than the coupling
required to remove it should stay.**

Two pieces of code that look identical but change for different reasons are not
duplication — they're a coincidence. Merging them creates a class that serves two masters.
This is the mistake behind more bad abstractions than any other.

### Fail fast

✅ `AvailabilityPatternValidator` rejects bad input at the boundary, so the algorithm never
defends against malformed strings.

✅ Spring Data validates derived queries at **startup**. `findByEmailsIn` (typo) refuses to
boot rather than failing on first use at 3am.

🐞 `findByEmailIn` **silently drops** unknown emails, producing a plausible-looking wrong
answer. That's the opposite of failing fast, and it's the worst failure mode there is:
**silent, incorrect, and undetectable from the response.**

### Tell, Don't Ask

✅ `event.isOrganisedBy(user)` — ask the object to make the judgement.
❌ `event.getAudiences().stream().anyMatch(a -> ...)` in three services — reach in, pull the
data out, decide externally, three times.

The first keeps behaviour with the data it needs. The second is how entities become anaemic
bags of getters and business rules end up scattered across services.

✅ Also: `eventScheduler.addAudience(user, role, status)`. The entity maintains its own
invariant (lazily creating the list, wiring both sides of the relationship). Callers can't
get it wrong.

### Make illegal states unrepresentable

✅ The single best example in the codebase:

```java
public class UserUpdateRequest {
    @NotNull private String name;      // no email field. At all.
}
```

"Email cannot be changed" is enforced by the **shape of the type**, not by a runtime check.
There is no `if (request.getEmail() != null) throw ...` to forget, no test needed, no way
for a future developer to break it accidentally.

**When you can choose between "validate it" and "make it unexpressible", choose the
second.** This is the highest-leverage design idea in this whole file.

⚠️ Counter-example in the same project: `startTime` as `String`. `"9999"`, `""`, `"abcd"`
are all representable and must be validated at runtime, in several places, forever. A
`LocalTime` (or a small `SlotTime` value class validating in its constructor) would make
those states unrepresentable.

### Principle of least astonishment

🐞 `EventScheduler.update()` copies every field **except `eventDate`**, sitting right below
a constructor that *does* copy it. A reader will assume reschedule can change the date. It
can't, silently.

🐞 `PUT /availability/defaults` replaces the whole week; omitting `"friday"` clears Friday.
Astonishing, and there's no way to update one day.

⚠️ `getCurrentTimeInMinFor()` returns `HHMM`, not minutes.

None of these are hard problems. **They are all fixed by a better name, a comment, or a
one-line guard** — and each one will cost somebody an hour.

---

## How to actually decide, in a code review

When someone says "this violates SOLID", the useful next question is always the same:

> **What change is coming, and does this design make that change cheap or expensive?**

Applied to this project:

| Likely change | Cheap or expensive today? | Why |
|---|---|---|
| Add a `MONTH` window | **Cheap** — one enum line | Data lives on the enum |
| Add a new 404-mapped exception | **Cheap** — extend `NotFoundException` | Handler binds the base type |
| Swap MySQL → PostgreSQL | **Cheap** — pom + URL | Repositories are interfaces |
| Replace the matrix with a sweep line | **Cheap** — one pure function | Utility is isolated and pure |
| Add real timezone support | **Expensive** — `varchar(4)`, `Date`, enum, algorithm | Assumption baked in 4 layers deep |
| Add a field to a user response only | **Expensive** — DTO inheritance | Parent is shared with the request |
| Check availability before booking | **Expensive** — circular dependency | Services depend on each other directly |
| Rename `monday` in the JSON | **Very expensive** — API + DB migration | One class, four roles |

**That table is what design quality actually means.** Not how many interfaces there are.

Notice the pattern: this codebase is well-designed along the axes where the author expected
change (window types, exception types, persistence) and poorly designed along the axes they
didn't (timezones, DTO evolution, cross-service rules). **That's normal, and it's why
design is a skill rather than a checklist** — you're predicting the future, and you'll be
partly wrong.

---

## Checkpoint

- [ ] Explain why adding `MONTH(30)` requires one line, using the actual code
- [ ] Explain why `ActionNotAllowed` is an Open/Closed failure
- [ ] Explain why the seven lines in `getWeeksAvailability()` should NOT be DRY'd
- [ ] Find the DTO inheritance bug Lombok warns about
- [ ] Give one example from this repo of "make illegal states unrepresentable"
