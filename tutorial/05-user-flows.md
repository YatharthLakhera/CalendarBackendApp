# 05 — User Flows, End to End

Copy-paste these. Have `spring.jpa.show-sql=true` on and a second terminal tailing
`logs/application.log`. **Watch the SQL while you do it** — that's where the learning is.

Set a shell variable as you go:

```bash
BASE=http://localhost:8080
```

---

## Flow 1: Two users get set up

### 1.1 Create Asha (the organiser)

```bash
curl -s -X POST $BASE/user -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com"}'
```

```json
{"name":"Asha","email":"asha@example.com","id":"11111111-2222-3333-4444-555555555555"}
```

```bash
ASHA=11111111-2222-3333-4444-555555555555   # substitute your real UUID
```

**In the SQL log** you'll see a single `insert into user_details (...)`. Notice
Hibernate generated the UUID *before* the insert — that's `GenerationType.UUID`, computed
in Java, not by the database. Contrast with `GenerationType.IDENTITY` on
`EventAudience.id`, where the DB assigns the value and Hibernate must read it back.

### 1.2 Create Bala (the participant)

```bash
curl -s -X POST $BASE/user -H 'Content-Type: application/json' \
  -d '{"name":"Bala","email":"bala@example.com"}'
BALA=<uuid from response>
```

### 1.3 Try to break it (do this, it's the point)

```bash
# Missing name → @NotNull fires because @Valid is present
curl -i -X POST $BASE/user -H 'Content-Type: application/json' \
  -d '{"email":"x@example.com"}'

# Malformed email → @Email fires
curl -i -X POST $BASE/user -H 'Content-Type: application/json' \
  -d '{"name":"X","email":"not-an-email"}'

# Duplicate email → 🐞 succeeds. It should not.
curl -i -X POST $BASE/user -H 'Content-Type: application/json' \
  -d '{"name":"Asha Clone","email":"asha@example.com"}'
```

That third one is the bug from [03-api-reference.md](03-api-reference.md). Now watch it
poison a *different* endpoint:

```bash
curl -i "$BASE/user/search?email=asha@example.com"
# 500 — IncorrectResultSizeDataAccessException: query did not return a unique result: 2
```

**This is the most valuable thing in this file.** A missing constraint on *write* broke an
endpoint on *read*, and the error message points at the search code, which is innocent.
Real production debugging looks exactly like this: the symptom is nowhere near the cause.

Clean up before continuing:

```sql
DELETE FROM user_details WHERE name = 'Asha Clone';
```

---

## Flow 2: Setting availability

### 2.1 Asha's weekly default

```bash
curl -i -X PUT $BASE/availability/defaults \
  -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{
        "timeZone":"IST",
        "monday":"1000:1300;1400:1800",
        "tuesday":"1000:1300;1400:1800",
        "wednesday":"1000:1300;1400:1800",
        "thursday":"1000:1300;1400:1800",
        "friday":"1000:1300;1400:1800",
        "saturday":"",
        "sunday":""
      }'
```

`200` with an empty body (the method returns `void`).

In MySQL:

```sql
SELECT name, default_availability FROM user_details WHERE name = 'Asha';
```

The whole object is one JSON string in one column — that's
`UserDefaultAvailabilityConverter` at work
([04](04-db-schema-and-orm.md#design-decision-3-the-json-column-and-convert)).

### 2.2 Bala's weekly default — narrower on purpose

```bash
curl -i -X PUT $BASE/availability/defaults \
  -H "userId: $BALA" -H 'Content-Type: application/json' \
  -d '{
        "timeZone":"IST",
        "monday":"1100:1200;1500:1700",
        "tuesday":"1100:1200;1500:1700",
        "wednesday":"1100:1200;1500:1700",
        "thursday":"1100:1200;1500:1700",
        "friday":"1100:1200;1500:1700",
        "saturday":"",
        "sunday":""
      }'
```

Asha 10:00–13:00 & 14:00–18:00, Bala 11:00–12:00 & 15:00–17:00. **Predict the
intersection now, before you run anything:** 11:00–12:00 and 15:00–17:00. Write it down.

### 2.3 Break the pattern validator

```bash
curl -i -X PUT $BASE/availability/defaults \
  -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"timeZone":"IST","monday":"1100:1000"}'      # start after end

curl -i -X PUT $BASE/availability/defaults \
  -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"timeZone":"IST","monday":"1002:1100"}'      # not a multiple of 5
```

Both are rejected — good. **Now look at the status code and body.** They're not the clean
`400 {"errorMessage":"Start time should be less than end time"}` you'd expect, because
Jackson wraps exceptions thrown inside deserializers
([02](02-request-lifecycle.md#jackson-deserialization--and-a-project-specific-twist)).
Write down exactly what you got — you'll fix it in
[13-exercises.md](13-exercises.md).

Also note: that second call **replaced** Asha's whole week with `monday` only. Re-run 2.1
before continuing. A full-replace `PUT` with no partial-update option is a real usability
trap, and you just fell into it.

### 2.4 A custom override — Bala takes a half day

Pick a concrete near-future weekday. Say `2025-01-14` (a Tuesday):

```bash
curl -i -X PUT $BASE/availability/custom \
  -H "userId: $BALA" -H 'Content-Type: application/json' \
  -d '{"date":"2025-01-14","timeZone":"IST","availability":"1100:1200","available":true}'
```

Now Bala's Tuesday is **only** 11:00–12:00 — the custom entry replaces the default
entirely, it does not intersect with it.

**Prove the `available` vs `isAvailable` trap to yourself:**

```bash
curl -i -X PUT $BASE/availability/custom \
  -H "userId: $BALA" -H 'Content-Type: application/json' \
  -d '{"date":"2025-01-15","timeZone":"IST","availability":"1100:1200","isAvailable":true}'
```

```sql
SELECT availability_date, is_available, availability FROM user_custom_availability;
```

The `2025-01-15` row has `is_available = 0`. You sent `true`. Jackson ignored
`isAvailable` because the property is named `available`, so the field kept its Java
default of `false` — and `false` means "blocked all day". **A typo in a JSON key silently
blocked someone's calendar with a `200 OK` response.** That is what "silent failure"
means, and it is why `spring.jackson.deserialization.fail-on-unknown-properties=true`
is worth considering.

Delete that row before continuing.

### 2.5 Block a whole day

```bash
curl -i -X PUT $BASE/availability/custom \
  -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"date":"2025-01-16","timeZone":"IST","available":false}'
```

`available:false` ⇒ `AvailabilityUtility` substitutes `""` for that date ⇒ no slots ⇒ the
intersection for any group containing Asha is empty on the 16th.

---

## Flow 3: Finding a common slot

### 3.1 Just yourself

```bash
curl -s "$BASE/availability/2025-01-14/DAY" -H "userId: $ASHA" | python3 -m json.tool
```

### 3.2 The real question — when can Asha and Bala meet?

```bash
curl -s "$BASE/availability/2025-01-14/DAY?emails=bala@example.com" \
  -H "userId: $ASHA" | python3 -m json.tool
```

Compare against the intersection you predicted in 2.2 (adjusted for Bala's 2.4 override:
just 11:00–12:00).

🐞 **It will not match, and the reason is a real bug.** The weekday rotation in
`AvailabilityUtility.getAvailabilityBucketsFor` is off by one, so a request starting on a
Tuesday reads the **Wednesday** column of each user's default schedule. Because this
project's sample data uses identical hours Mon–Fri, the bug is nearly invisible — which is
precisely why it survived. Set a deliberately distinctive Wednesday
(`"wednesday":"0900:0930"`) and re-run: you'll see Wednesday's hours appear on a Tuesday
request.

Full proof and the one-line fix: [07-lld-availability-engine.md](07-lld-availability-engine.md#-bug-1-the-weekday-rotation-is-off-by-one).

### 3.3 A full week

```bash
curl -s "$BASE/availability/2025-01-13/WEEK?emails=bala@example.com" \
  -H "userId: $ASHA" | python3 -m json.tool
```

Seven days of intersections. Note the response has no pagination and no `Cache-Control`;
this is the most expensive endpoint in the app.

### 3.4 Empty results, and why you can't tell them apart

```bash
# (a) Typo'd email → silently ignored, you get Asha-only availability presented as a group answer
curl -s "$BASE/availability/2025-01-14/DAY?emails=blaa@example.com" -H "userId: $ASHA"

# (b) A blocked day → correctly empty
curl -s "$BASE/availability/2025-01-16/DAY?emails=bala@example.com" -H "userId: $ASHA"

# (c) Availability that runs to the end of the day → 🐞 wrongly empty
curl -i -X PUT $BASE/availability/custom -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"date":"2025-01-17","timeZone":"IST","availability":"2300:2355","available":true}'
curl -s "$BASE/availability/2025-01-17/DAY" -H "userId: $ASHA"

# (d) Two back-to-back ranges → 🐞 the touching slot disappears from the middle
curl -i -X PUT $BASE/availability/custom -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"date":"2025-01-18","timeZone":"IST","availability":"1000:1100;1100:1200","available":true}'
curl -s "$BASE/availability/2025-01-18/DAY" -H "userId: $ASHA"
```

Only (b) is behaving as designed. (a) returns a *wrong answer that looks right* — worse
than an error. (c) returns nothing at all. (d) returns `1000-1055` and `1105-1200` with an
inexplicable five-minute hole at 11:00. All four responses are shaped identically, and the
API tells you nothing about which situation you're in.

Both (c) and (d) are dissected in
[07-lld-availability-engine.md](07-lld-availability-engine.md).

**The lesson:** when a result can be empty for several reasons, an API that only says
"empty" is unfinished. Real APIs return something like
`{"buckets":[], "unresolvedEmails":["blaa@example.com"]}` — or reject unknown emails with
a `400` outright.

---

## Flow 4: Booking, RSVP, rescheduling, cancelling

### 4.1 Book it

```bash
curl -s -X POST $BASE/event -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{
        "title":"Design review",
        "description":"Walk through the availability engine",
        "eventDate":"2025-01-14",
        "startTime":"1100",
        "endTime":"1200",
        "timeZone":"IST",
        "audienceReq":[{"email":"bala@example.com","role":"PARTICIPANT"}]
      }' | python3 -m json.tool
```

```bash
EVENT=<eventId from response>
```

Asha appears in `audienceRes` as `ORGANISER` / `ACCEPTED` even though she wasn't in the
request — `EventSchedulerService.addEventFor` adds her explicitly.

**In the SQL log, count the queries.** One `select` per participant email, plus the user
lookup, plus inserts. Two participants = 2 selects. Fifty participants = 50 selects, in a
loop, one round trip each. That's the **N+1 problem** and it's on
[11-production-gaps.md](11-production-gaps.md#the-n1-query-problem). The fix already
exists in the codebase — `UserRepository.findByEmailIn` loads them all in one query — it
just isn't used here.

### 4.2 Watch the slot disappear

```bash
curl -s "$BASE/availability/2025-01-14/DAY?emails=bala@example.com" \
  -H "userId: $ASHA" | python3 -m json.tool
```

The 11:00–12:00 bucket is gone; the event appears in `events`. **That round trip — set
availability, compute intersection, book, recompute — is the entire product.** If you
understand it, you understand this codebase.

### 4.3 The extremities rule

Try booking 12:00–13:00 next. Per the README, a meeting ending at 12:00 blocks the instant
12:00, so a 12:00 start is *not* offered. The next available start is 12:05. This falls
out of the inclusive `<=` comparisons in `processAvailabilityMatrixFor`
([07](07-lld-availability-engine.md)). It's a documented decision, not a bug — but it's
the kind of thing that generates support tickets forever.

### 4.4 Book on top of an existing meeting — it works 🐞

```bash
curl -s -X POST $BASE/event -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"title":"Double booked","eventDate":"2025-01-14","startTime":"1100","endTime":"1200",
       "timeZone":"IST","audienceReq":[{"email":"bala@example.com","role":"PARTICIPANT"}]}'
```

`200`. Two overlapping meetings for both people. Also try `"startTime":"0300"` (outside
everyone's hours) and `"endTime":"1000"` with `"startTime":"1100"` (ends before it
starts) — all accepted.

**The availability API is advisory; the booking API enforces nothing.** Understanding
*why that's hard to fix properly* — two concurrent bookings can both pass an availability
check and both commit — is the difference between a junior and a mid-level engineer. See
[11-production-gaps.md](11-production-gaps.md#double-booking-is-not-prevented).

### 4.5 RSVP

```bash
curl -i -X PUT "$BASE/event/$EVENT/status/DECLINED" -H "userId: $BALA"
```

```sql
SELECT role, status FROM event_audience;
```

Now re-run 4.2. 🐞 The slot is **still blocked** even though Bala declined. The status was
recorded and nothing reads it: `AvailabilityUtility` blocks every event returned by the
query regardless of RSVP. A one-line filter fixes it — see
[13-exercises.md](13-exercises.md).

### 4.6 Authorisation — the 500 that should be a 403

```bash
curl -i -X DELETE "$BASE/event/$EVENT" -H "userId: $BALA"
```

`500 {"errorMessage":"An error occurred: Unable to perform the action"}`.

The check itself is correct (`EventScheduler.isOrganisedBy`); only the HTTP mapping is
wrong, because `ActionNotAllowed extends RuntimeException` instead of a mapped type
([02](02-request-lifecycle.md#step-8--exceptions-become-http-responses)).

### 4.7 Reschedule, and notice what doesn't change

```bash
curl -s -X PUT "$BASE/event/$EVENT/update" -H "userId: $ASHA" -H 'Content-Type: application/json' \
  -d '{"title":"Design review v2","description":"Now with more matrices",
       "eventDate":"2025-01-20","startTime":"1500","endTime":"1600","timeZone":"IST",
       "audienceReq":[{"email":"bala@example.com","role":"PARTICIPANT"}]}' | python3 -m json.tool
```

Two things to observe:

1. ⚠️ `eventDate` is **still 2025-01-14**. `EventScheduler.update()` doesn't copy it. You
   cannot move a meeting to another day.
2. Bala's status reset from `DECLINED` to `PENDING` — the audience list was deleted and
   rebuilt. Arguably correct (the meeting changed, re-confirm please), but it is a
   *decision* nobody wrote down.

**In the SQL log:** `delete from event_audience ...` followed by `insert into
event_audience ...`. That's `orphanRemoval = true` plus `@Transactional`. Remove the
`@Transactional` and this method becomes genuinely unsafe.

### 4.8 Cancel

```bash
curl -i -X DELETE "$BASE/event/$EVENT" -H "userId: $ASHA"
```

```sql
SELECT COUNT(*) FROM event_audience WHERE event_id = UNHEX(REPLACE('<event-uuid>','-',''));
-- 0, via CascadeType.ALL
```

Hard delete. Nothing records that this meeting ever existed or who cancelled it.

---

## The complete flow, one diagram

```
  Asha                         System                              Bala
   │                             │                                  │
   ├── POST /user ───────────────▶ insert user_details ──▶ UUID      │
   │                             │                                  │
   ├── PUT /availability/defaults▶ user_details.default_availability │
   │                             │   (JSON blob via @Convert)        │
   │                             │◀───── PUT /availability/defaults ─┤
   │                             │                                  │
   │                             │◀───── PUT /availability/custom ───┤
   │                             │  (row in user_custom_availability;│
   │                             │   overrides default for that date)│
   │                             │                                  │
   ├── GET /availability/{d}/DAY ▶ load defaults + customs + events  │
   │     ?emails=bala@...        │ build 288-slot matrix per day     │
   │                             │ +1 per participant available      │
   │                             │ −1 per booked event               │
   │◀──── slots where count ─────┤ emit runs where count==N          │
   │      == participantCount    │                                  │
   │                             │                                  │
   ├── POST /event ──────────────▶ insert event_scheduler            │
   │   (⚠️ no availability check) │ + N × event_audience (cascade)    │
   │                             │   organiser=ACCEPTED, others=PENDING
   │                             │                                  │
   │                             │◀── PUT /event/{id}/status/ACCEPTED┤
   │                             │    (⚠️ never read back)           │
   │                             │                                  │
   ├── PUT /event/{id}/update ───▶ @Transactional: update + wipe     │
   │   (organiser only)          │ audiences (orphanRemoval) + re-add│
   │                             │                                  │
   ├── DELETE /event/{id} ───────▶ hard delete + cascade             │
```

## Checkpoint

- [ ] You can go from zero to a booked meeting between two users using only `curl`
- [ ] You watched a slot disappear from the intersection after booking it
- [ ] You reproduced the duplicate-email bug and saw it break `/user/search`
- [ ] You reproduced the `isAvailable` → `available` silent failure
- [ ] You can name three different causes of an empty availability response, and say
      which of them are bugs
