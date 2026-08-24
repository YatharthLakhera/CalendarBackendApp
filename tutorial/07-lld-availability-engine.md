# 07 — LLD: The Availability Engine

`src/main/java/com/calendar/app/utils/AvailabilityUtility.java` — 205 lines, and the only
genuinely non-trivial algorithm in the repository. Everything else is CRUD.

Read this file with the source open beside you.

## The problem it solves

> Given N people, each with their own recurring weekly schedule, per-date overrides, and
> already-booked meetings — find every time window in the next 1 or 7 days where **all N
> are simultaneously free**.

## Why not the obvious approach?

The obvious approach is interval intersection: take person A's list of free ranges,
intersect with B's, intersect the result with C's, then subtract every booked meeting.

That works, and it's O(total intervals × log) with sorting. But you have to get right:
sorting, merging adjacent intervals, three-way overlap cases, subtraction splitting one
interval into two, empty results at each stage. It's maybe 80 lines of fiddly, easy-to-
break code.

This project chooses a **discretisation** instead: chop the day into fixed slots, count,
and read the answer off. Much easier to get right, much easier to explain, and it makes a
hard problem trivially composable — *anything* that affects availability becomes "+1 or
−1 on a range of slots."

**That trade — replace clever geometry with a dumb grid — is a genuinely good engineering
instinct, and it's the main thing to take away from this file.**

## The core idea in one picture

```
 Day divided into 288 slots of 5 minutes    (288 = 24 × 60 ÷ 5)

 slot index:   0     1     2   ...  120   121  ...  216  ...  287
 clock time: 0000  0005  0010  ...  1000  1005 ... 1800  ... 2355

 Step 1: +1 for every slot each participant is available
         Asha  10:00–13:00, 14:00–18:00
         Bala  11:00–12:00, 15:00–17:00

   time    10:00  11:00  12:00  13:00  14:00  15:00  16:00  17:00  18:00
   Asha      1      1      1      1      1      1      1      1      1
   Bala      0      1      1      0      0      1      1      1      0
           ─────────────────────────────────────────────────────────────
   sum       1      2      2      1      1      2      2      2      1

 Step 2: −1 for every slot covered by a booked event
         (an event with 2 of our participants appears twice in the query result,
          so it decrements twice — but see "does that matter?" below)

         Meeting 11:00–12:00 with both:
   sum       1      0      0      1      1      2      2      2      1

 Step 3: emit maximal runs where sum == participantCount (2)

   result:  15:00–17:00
```

Once you see that, the whole file is bookkeeping.

## Walking the code

### The constant

```java
private static final int AVAILABILITY_MATRIX_LENGTH = 288;
```

288 = 24 h × 60 min ÷ 5 min. **This constant is where the "all times must be multiples of
5" rule actually comes from** — `AvailabilityPatternValidator` enforces the rule at the
API boundary, but the *reason* is here. Two files, one invariant, and nothing links them.

⚠️ A comment (or better, `24 * 60 / SLOT_MINUTES` with `SLOT_MINUTES = 5`) would make the
relationship explicit and make the granularity changeable in one place. As written,
supporting 1-minute precision means finding every `288`, every `5`, and every `% 5` in the
codebase. **Magic numbers aren't bad because they're numbers; they're bad because they
hide a relationship.**

### Entry point

```java
public static AvailabilityResponse availabilityResponseBuilder(
        UserDetails organiser, List<UserDetails> participants,
        List<UserCustomAvailability> customAvailabilities,
        List<EventScheduler> eventSchedulers,
        Date fromDate, AvailabilityWindowType windowType)
```

Look at what this signature does *not* contain: no repository, no `EntityManager`, no
Spring anything. **All I/O already happened in `AvailabilityService`; this function is
pure.** Same inputs → same outputs, no side effects, no database.

That is why the class can be `@NoArgsConstructor(access = AccessLevel.PRIVATE)` with only
`static` methods, and it is why you could unit-test the entire engine with plain
constructors and zero mocking. (Nobody has. See [13-exercises.md](13-exercises.md).)

```java
Map<UUID, List<UserCustomAvailability>> userBasedCustomAvailabilitiesMap =
        customAvailabilities.stream().collect(Collectors.groupingBy(UserCustomAvailability::getUserId));
```

One query returned every participant's custom entries in one flat list; this regroups them
by user **in memory**. That is the right shape: one query for N users, then group locally.
The alternative — a query per user inside the loop — is the N+1 pattern that plagues
`EventSchedulerService`. Same codebase, and here it's done correctly. Notice the contrast.

### Per participant: turn schedules into buckets

```java
private static List<AvailabilityBucket> getAvailabilityBucketsFor(
        Date fromDate, AvailabilityWindowType windowType,
        UserDetails participant, List<UserCustomAvailability> customAvailabilities)
```

```java
List<String> weeksAvailability = new ArrayList<>();
if (participant.getDefaultAvailability() != null) {
    weeksAvailability = participant.getDefaultAvailability().getWeeksAvailability();
}
```

The null check exists because `default_availability` is nullable — a brand-new user has no
schedule. Git history shows this was added in commit `ebf47e8` "Handling null values" and
`4ce22b3` "Handled empty default slots", i.e. it was found by crashing. That is normal;
what matters is noticing that **every nullable column is a `NullPointerException` waiting
in whichever code path forgot.**

`getWeeksAvailability()` (in `UserDefaultAvailability`) returns a **fresh `ArrayList`**
every call, with `null` normalised to `""`. That freshness matters more than it looks —
the very next line mutates the list:

```java
Collections.rotate(weeksAvailability, - dayOfWeek.getValue());
```

If `getWeeksAvailability()` returned a cached or shared list, this rotation would corrupt
the entity's state for every subsequent participant in the loop. **Returning a defensive
copy from a getter is the thing that makes the caller's mutation safe.** It's accidental
here, but it's a habit worth adopting deliberately.

### 🐞 Bug 1: the weekday rotation is off by one

The goal: the list is always `[monday, tuesday, ..., sunday]`, and the loop wants index 0
to mean "the day `fromDate` falls on". So rotate the list until `fromDate`'s weekday is
first.

`java.time.DayOfWeek` values are **1-based**: `MONDAY.getValue() == 1` … `SUNDAY.getValue() == 7`.
The list is **0-based**: index 0 is Monday.

Rotating by `-getValue()` therefore overshoots by exactly one. Verified:

```
fromDate is MONDAY    (value=1) -> index 0 maps to: tue   ← should be mon
fromDate is TUESDAY   (value=2) -> index 0 maps to: wed   ← should be tue
fromDate is WEDNESDAY (value=3) -> index 0 maps to: thu   ← should be wed
...
fromDate is SUNDAY    (value=7) -> index 0 maps to: mon   ← should be sun
```

**Every request reads the wrong day's schedule — always the next day's.**

The fix is one character short of trivial:

```java
Collections.rotate(weeksAvailability, -(dayOfWeek.getValue() - 1));
// or, equivalently and more clearly:
Collections.rotate(weeksAvailability, -dayOfWeek.ordinal());
```

**Why did this survive?** Because every example in the README sets Monday–Sunday to the
*same* hours. With identical days, an off-by-one rotation is undetectable. The bug is
invisible to exactly the test data the author used.

**That is the lesson, and it's bigger than this bug:** test data that is uniform cannot
detect ordering errors. When you write test fixtures, make every value distinguishable —
`monday: "0900:0930"`, `tuesday: "1000:1030"`, ... — so that a mix-up *has* to show up.
This applies to array indices, column mappings, and API field ordering alike.

Reproduce it: set seven distinct schedules, request a Tuesday, watch Wednesday's hours
come back.

### Default vs. custom, per day

```java
for (int day = 0; day < windowType.getDays(); day++) {
    Date currentDate = DateUtils.addDays(fromDate, day);
    String availabilityString;
    if (customAvailabilityMap.containsKey(currentDate)) {
        availabilityString = customAvailabilityMap.get(currentDate).isAvailable()
                ? customAvailabilityMap.get(currentDate).getAvailability() : "";
    } else if (!weeksAvailability.isEmpty()) {
        availabilityString = weeksAvailability.get(day % 7);
    } else {
        continue;
    }
    availabilityBuckets.addAll(getAvailabilityBucketsFor(currentDate, availabilityString));
}
```

The precedence rule — **custom overrides default, entirely** — is expressed in five lines,
and it reads exactly like the product rule. Good code often looks like this: the `if`
structure *is* the specification.

`day % 7` lets the loop run past 7 days without an index error, so adding a `MONTH(30)`
window type would work here unchanged. That's the Open/Closed Principle showing up for
free ([10-design-principles.md](10-design-principles.md)).

⚠️ **A landmine in `containsKey(currentDate)`.** These are `java.util.Date` objects
compared by exact millisecond. `fromDate` comes from `SimpleDateFormat.parse` (midnight,
JVM default timezone); `currentDate` from `DateUtils.addDays`; the map keys come from
Hibernate as `java.sql.Date` (midnight, JDBC's timezone handling). They match today
because everything is midnight local and `java.sql.Date` doesn't override `equals`. Change
the JVM timezone, cross a DST boundary with `addDays`, or upgrade a driver, and this
silently stops matching — customs would be ignored with no error at all.

(Related trap worth carrying with you: `java.sql.Timestamp.equals(java.util.Date)` is
**asymmetric** — `a.equals(b)` and `b.equals(a)` can disagree, which breaks `HashMap` and
`List.contains` in ways that look like the JVM has gone mad. This is one of the strongest
arguments for `java.time.LocalDate`; see [09-java-gaps.md](09-java-gaps.md#javautildate-in-2024).)

### Parsing a string into buckets

```java
private static List<AvailabilityBucket> getAvailabilityBucketsFor(Date availabilityDate, String availabilityString) {
    if (StringUtils.isBlank(availabilityString)) return new ArrayList<>();
    // Will add timezone support here
    List<AvailabilityBucket> result = new ArrayList<>();
    for (String[] timings : AvailabilityParseUtility.parse(availabilityString)) {
        result.add(new AvailabilityBucket(availabilityDate, timings[0], timings[1]));
    }
    return result;
}
```

Note the **method overloading**: two private methods named `getAvailabilityBucketsFor`
with different parameter lists. Java picks by signature at compile time. It reads fine
here, but overloads that differ only in parameter *types* (not counts) are a classic
source of "why is the wrong method being called?" — especially with `null` arguments or
autoboxing.

`AvailabilityBucket`'s constructor does `Integer.parseInt(startTime)` once and stores the
result in `@JsonIgnore private int startTimeValue`. **Parse once at construction, not
inside the 288-iteration loop.** The inner loop runs `days × 288 × buckets` times; parsing
strings in there would be the difference between microseconds and milliseconds. Cheap
work done at the boundary, hot loop kept arithmetic-only — that's the pattern.

And `// Will add timezone support here` is honest, useful, and exactly where the change
would go. A TODO that names the *place* is worth ten that name the *wish*.

### The matrix

```java
int[][] availabilityMatrix = new int[windowType.getDays()][AVAILABILITY_MATRIX_LENGTH];
for (int[] matrix : availabilityMatrix) {
    Arrays.fill(matrix, 0);
}
```

The `Arrays.fill(..., 0)` is **redundant** — the JVM guarantees `new int[n]` is
zero-initialised (unlike C's `malloc`). The comment above it says "Explicitly initialize
the matrix with zeros." Harmless, and defensible as documentation, but know the language
guarantee: numeric fields and array elements are `0`, `boolean` is `false`, references are
`null`, always. (`int[288]` of garbage would be a C bug; in Java it cannot happen.)

### Increase, then decrease

```java
processAvailabilityMatrixFor(availabilityMatrix, ProcessType.INCREASE, dateBasedAvailabilities, fromDate, windowType);
// ...
processAvailabilityMatrixFor(availabilityMatrix, ProcessType.DECREASE, dateBasedAvailabilities, fromDate, windowType);
```

```java
private enum ProcessType {
    INCREASE(1), DECREASE(-1);
    private final int value;
    ...
}
```

A private enum carrying `+1` / `−1` so **one method** handles both passes. Compare the
alternative — a `boolean isIncrease` parameter, and a call site reading
`processAvailabilityMatrixFor(matrix, true, ...)` where `true` means nothing to a reader.
Enum-instead-of-boolean is a small, high-value habit: it names the intent at the call
site and it extends (you could add `TENTATIVE(0)` for `MAYBE` RSVPs) without changing the
signature.

The inner loop:

```java
for (int timeWindow = 0; timeWindow < AVAILABILITY_MATRIX_LENGTH; timeWindow++) {
    int curTimeInMin = getCurrentTimeInMinFor(timeWindow);
    for (AvailabilityBucket availabilityBucket : availabilityBuckets) {
        if (availabilityBucket.getStartTimeValue() <= curTimeInMin
                && curTimeInMin <= availabilityBucket.getEndTimeValue()) {
            availabilityMatrix[day][timeWindow] += processType.getValue();
        }
    }
}
```

### The `HHMM`-as-integer trick

```java
private static int getCurrentTimeInMinFor(int timeWindow) {
    int hour = ((timeWindow * 5) / 60) * 100;
    int min  = (timeWindow * 5) % 60;
    return hour + min;      // slot 120 → 1000, slot 216 → 1800
}
```

The method name says "in min" but it returns `HHMM` — slot 120 returns `1000`, meaning
10:00, not 1000 minutes. **The name is wrong and cost me a re-read; it will cost you one
too.** `getClockTimeForSlot` would have been free.

The interesting part is *why* this encoding works at all. `HHMM` arithmetic is broken:
`1100 − 1000 = 100`, which is not 60 minutes. But **comparison** is fine, because `HHMM`
is monotonically increasing in real time — later time ⇒ larger number, always. The
algorithm only ever compares (`<=`), never subtracts. It's correct, and it's correct for a
reason worth articulating: *you can use a lossy encoding as long as you only use the
operations it preserves.* Add one subtraction anywhere and it breaks silently.

### ⚠️ Inclusive bounds, and the surprising consequence

`startTime <= t && t <= endTime` — **both ends inclusive.** A 10:00–11:00 range marks slot
`1100` as available, so a 10:00–11:00 *meeting* also blocks slot `1100`, and the next
bookable start is 11:05.

The README documents this as an assumption. But run the simulation on two ranges a user
would very reasonably write:

```
"1000:1100;1100:1200"   (two back-to-back blocks)
  →  1000-1055  and  1105-1200
```

🐞 **The 11:00 slot vanishes.** Both ranges cover `1100`, so it counts `+2` while
`participantCount` is `1`, and `== participantCount` fails. The user declared themselves
*more* available and got *less*.

The same bug bites overlapping ranges:

```
"1000:1200;1100:1300"   →  1000-1055  and  1205-1300
```

The entire 11:00–12:00 window — where the user is unambiguously free — is reported as
unavailable.

**The root cause is an unstated invariant:** the counting scheme assumes *each participant
contributes at most +1 to any slot*, which requires each user's ranges to be disjoint and
non-touching. Nothing enforces that. `AvailabilityPatternValidator` checks `start < end`
and multiples of 5, but never checks ranges against each other.

Two possible fixes, and the choice is a genuine design decision:
- **Validate**: reject overlapping/touching ranges at the API boundary. Cheap, but rejects
  input a user considers obviously valid.
- **Normalise**: merge each user's ranges before counting (or clamp each participant's
  contribution to 1 per slot). Accepts anything, always correct. More code.

Normalising is the better answer, because "be liberal in what you accept" applies here —
`"1000:1100;1100:1200"` has an unambiguous meaning and refusing it is user-hostile.

### 🐞 Bug 2: availability that reaches the end of the day is silently dropped

```java
Integer start = null;
for (int timeWindow = 0; timeWindow < AVAILABILITY_MATRIX_LENGTH; timeWindow++) {
    int curTimeInMin = getCurrentTimeInMinFor(timeWindow);
    if (availabilityMatrix[day][timeWindow] == participantCount && start == null) {
        start = curTimeInMin;
    } else if (availabilityMatrix[day][timeWindow] != participantCount && start != null) {
        finalAvailability.add(new AvailabilityBucket(curDate, start, getCurrentTimeInMinFor(timeWindow - 1)));
        start = null;
    }
}
// ← loop ends here. If start != null, nothing is emitted.
```

A run is only emitted when it **ends**. If a run is still open when the loop finishes —
i.e. the participant is available through 23:55 — it is never added.

Verified:

```
availability "2300:2355", one participant  →  (EMPTY)
```

The fix is three lines after the loop:

```java
if (start != null) {
    finalAvailability.add(new AvailabilityBucket(curDate, start, getCurrentTimeInMinFor(AVAILABILITY_MATRIX_LENGTH - 1)));
}
```

**This is the single most common bug shape in all of programming**: a loop that flushes
accumulated state on a transition, with no flush after the final iteration. You will write
it yourself — in CSV parsers, in batch jobs, in stream aggregators. Learn the shape now
and you'll spot it for the rest of your career.

`start` is declared as `Integer`, not `int`, specifically so `null` can mean "no run in
progress". That's a legitimate use of boxing as a sentinel; the cost is that every
comparison risks an unintended `NullPointerException` on auto-unboxing.

Note also the emitted end is `getCurrentTimeInMinFor(timeWindow - 1)` — the *previous*
slot, because the current slot is the one that broke the run. Correct, and easy to get
wrong by one.

## Complexity, and the version you'd write next

Current: **O(days × 288 × buckets)**, with `buckets ≈ participants × ranges_per_day`.

For 10 participants × 3 ranges over a week: 7 × 288 × 30 ≈ 60,000 iterations of trivial
integer work. Sub-millisecond. **This is fast enough and should not be optimised.** The
README's reasoning ("we are not expecting meeting to be booked for huge number of
participants... Expectation is around 10") is exactly the right way to justify a design.

But when it isn't fast enough — say 500 participants — the fix is not a faster loop, it's
a **difference array**, and it's worth knowing because it's a standard technique:

```java
// Instead of touching every slot in every range (O(288 × buckets)):
int[] delta = new int[LEN + 1];
for (AvailabilityBucket b : buckets) {
    delta[slotOf(b.getStartTimeValue())]     += 1;      // O(1) per bucket
    delta[slotOf(b.getEndTimeValue()) + 1]   -= 1;
}
int running = 0;
for (int i = 0; i < LEN; i++) {                          // one pass
    running += delta[i];
    matrix[day][i] = running;
}
```

**O(buckets + 288)** instead of **O(288 × buckets)**. Same answer, mark the edges instead
of painting the interior. (Also known as a *sweep line* or *prefix sum*.) It would make
this file shorter *and* faster — but only do it after you've measured, and after the
correctness bugs above are fixed. **Correct and slow beats fast and wrong**, every time.

## The service side: what feeds the engine

```java
// AvailabilityService.getAvailabilityFor
Date toDate = DateUtils.addDays(fromDate, windowType.getDays());
UserDetails organiser = userService.getUserFor(userId);
List<UserDetails> participants = !emails.isEmpty() ? userService.getUserFor(emails) : new ArrayList<>();
participants.add(organiser);
List<EventScheduler> eventSchedulers = eventSchedulerService.getEventBy(participants, fromDate, toDate);
List<UserCustomAvailability> customAvailabilityModels =
        userCustomAvailabilityRepository.findByUserIdsAndDateRange(..., fromDate, toDate);
```

Four queries, then one pure call. Good shape. Three things to notice:

**(a) 🐞 Unknown emails vanish silently.** `getUserFor(List<String>)` → `findByEmailIn`,
which returns only rows that matched. Typo one address and you get the intersection of a
smaller group, presented as if it were the answer you asked for. **A wrong answer that
looks right is worse than an error.** Fix: compare `emails.size()` to
`participants.size()` and reject, or return the unresolved addresses in the response.

**(b) ⚠️ The window is one day too wide for events.**
`toDate = fromDate + windowType.getDays()`, so a `DAY` request queries a 2-day inclusive
`BETWEEN` and a `WEEK` request queries 8 days. The matrix only covers `getDays()` days, so
the extra events can't affect slot computation — but they *do* land in the `events` array
of the response. You'll see a next-day meeting appear on a single-day query. Should be
`addDays(fromDate, windowType.getDays() - 1)`.

**(c) ⚠️ The event query returns duplicate rows.**

```java
@Query("SELECT e FROM EventScheduler e JOIN e.audiences aud WHERE aud.user.userId IN :userIds AND e.eventDate BETWEEN :startDate AND :endDate")
```

JPQL `JOIN` without `DISTINCT` yields **one row per matching join tuple**. An event with
three of your participants comes back three times.

For the *matrix*, that's harmless: it decrements three times instead of once, and since
the emit condition is exact equality (`== participantCount`), any decrement already blocks
the slot. Going to `−2` blocks it no harder than `−1`.

For the *response*, it's a real defect: `events` contains the same meeting repeated once
per attendee in your list. Add `DISTINCT` (or `SELECT DISTINCT e`) and the duplicates go
away with no effect on the slots.

**This is a good thing to sit with.** A latent defect in a query was invisible because a
downstream exact-equality check happened to mask it, and it only became visible in a
different consumer of the same data. Two independent things had to be true for the bug to
hide. That is how real bugs behave.

## Summary of findings

| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | 🐞 Weekday rotation off by one — every request reads the next day's schedule | **High** | `-(dayOfWeek.getValue() - 1)` |
| 2 | 🐞 Runs open at 23:55 are never emitted | **High** | Flush after the loop |
| 3 | 🐞 Touching/overlapping ranges from one user create holes | **High** | Merge ranges, or clamp contribution to 1/slot |
| 4 | 🐞 Unknown participant emails silently dropped | **High** | Reject, or report unresolved |
| 5 | ⚠️ Duplicate events in the response (JPQL join) | Medium | `SELECT DISTINCT e` |
| 6 | ⚠️ Event window one day too wide | Low | `getDays() - 1` |
| 7 | ⚠️ `getCurrentTimeInMinFor` returns `HHMM`, not minutes | Low | Rename |
| 8 | ⚠️ `288` and `5` are unexplained magic numbers | Low | Derive from one constant |
| 9 | ⚠️ Date-key matching depends on JVM timezone | Latent | `LocalDate` |

**Nine findings in 205 lines is not a bad file.** It's an ordinary one. Every codebase you
join looks like this — the difference between engineers is whether they can see it.

## Checkpoint

- [ ] Explain the counting scheme without looking at the code
- [ ] Explain why `HHMM`-as-int is safe for `<=` but not for `−`
- [ ] Reproduce the off-by-one with seven distinct weekday schedules
- [ ] Reproduce the dropped end-of-day run with `"2300:2355"`
- [ ] Reproduce the hole with `"1000:1100;1100:1200"`
- [ ] Sketch the difference-array version and state its complexity
