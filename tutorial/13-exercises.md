# 13 — Exercises

Work these in order. Each one has a **goal** (what you're proving you understand), not just
a task. Don't look at the hints until you've genuinely tried.

Work on a branch:

```bash
git checkout -b tutorial/<your-name>
```

---

## Level 0 — Prove the environment works

### 0.1 Get it running
Follow [01](01-setup-and-first-run.md). Create a user, fetch them back, find the row in
MySQL with the `UNHEX(REPLACE(...))` trick.

### 0.2 Read the SQL
Turn on `show-sql`, book an event with three participants, and **count the queries**.
Write the number down and explain each one.
*Goal: you never again assume an ORM call is one query.*

### 0.3 Fix the Maven wrapper
The repo has `mvnw` but no `.mvn/wrapper/`. Regenerate it:
```bash
mvn wrapper:wrapper -Dmaven=3.9.9
```
Commit the result. Then explain in one sentence why a wrapper exists at all.

---

## Level 1 — Read the code

### 1.1 Trace a request without running it
Take `PUT /event/{eventId}/status/{status}`. Write down every file, in order, from socket
to SQL. Include argument resolution and where the exception could come from.
*Goal: you can navigate an unfamiliar Spring app by reading.*

### 1.2 Where does `StringUtils` come from?
Run `mvn dependency:tree`. Find `commons-lang3`. Explain in three sentences why removing
the Swagger dependency would break the build.
*Goal: you understand transitive dependencies.* ([09](09-java-gaps.md#the-dependency-that-isnt-declared))

### 1.3 Prove Lombok is real
```bash
mvn -o compile
javap -p target/classes/com/calendar/app/db/entity/EventAudience.class
```
List five methods that exist in the `.class` but not in the `.java`.

### 1.4 Draw the schema from the entities alone
Without opening `db_schema.sql`, draw the four tables, their columns, and their
relationships using only the `@Entity` classes. Then diff against the real schema.
*Goal: you can read a JPA mapping as a schema.*

---

## Level 2 — The bug hunt

Each of these is a **real bug** in the repo. For each: reproduce it with a `curl` or a
test, then fix it, then write a test that fails before your fix and passes after.

**The test is the deliverable, not the fix.** A fix without a test is a bug waiting to come
back.

### 2.1 🐞 The weekday off-by-one
Set seven *distinct* weekday schedules. Request a Tuesday. Observe you get Wednesday's
hours.
<details><summary>Hint</summary>
`AvailabilityUtility.getAvailabilityBucketsFor`, the `Collections.rotate` line.
`DayOfWeek` is 1-based; the list is 0-based.
</details>

### 2.2 🐞 The vanishing end of day
Set a custom availability of `"2300:2355"` for a date and request it. You get nothing.
<details><summary>Hint</summary>
`generatingAvailabilityFrom`. A run is only emitted when it *ends*. What if it never does?
</details>

### 2.3 🐞 The five-minute hole
Set `"1000:1100;1100:1200"` — one continuous block written as two. Observe the 11:00 slot
disappears.
Then decide: reject overlapping ranges, or merge them? **Write down which you chose and
why** before you implement it. ([07](07-lld-availability-engine.md#-inclusive-bounds-and-the-surprising-consequence))

### 2.4 🐞 The 500 that should be a 403
Have a non-organiser delete an event. Fix the status code.
<details><summary>Hint</summary>
Two options: give `ActionNotAllowed` a mapped base class, or add an `@ExceptionHandler`.
Which is more Open/Closed? ([10](10-design-principles.md#o--openclosed))
</details>

### 2.5 🐞 The duplicate user
`POST /user` twice with the same email, then `GET /user/search?email=...`.
Fix it properly — the fix belongs in **two** places (database and application), and you
should be able to say what each one protects against.

### 2.6 🐞 Silently dropped emails
Request availability with a misspelled participant email. Note you get a *plausible wrong
answer* rather than an error. Fix it.
*Goal: you recognise silent failure as the worst failure mode.*

### 2.7 🐞 Decline doesn't free the slot
RSVP `DECLINED`, then check availability. Still blocked. Fix it — and think about whether
`MAYBE` should block or not, and who decides that.

### 2.8 🐞 The thread-unsafe formatters
Find the two `static SimpleDateFormat` fields. Explain why they're dangerous, then fix
them. **Bonus:** write a test with 50 threads parsing concurrently that fails before your
fix. (It's flaky by nature — that's part of the lesson.)

### 2.9 🐞 Duplicate events in the response
Book an event with 3 participants, then request availability for all 3. Count how many
times that event appears in `events`. Fix the JPQL.

---

## Level 3 — Make it safe

### 3.1 Add the missing constraints
Write the `ALTER TABLE` statements for unique email and unique
`(user_id, availability_date)`. Then answer: **why is a database constraint a better fix
than a Java check** for the second one? ([11](11-production-gaps.md#no-schema-migrations))

### 3.2 Introduce Flyway
Add the dependency, move `db_schema.sql` to `V1__initial_schema.sql`, add your constraints
as `V2`. Delete the duplicate schema file. Verify a fresh database builds itself on
startup.

### 3.3 Validate the event API
`POST /event` accepts a null title, an empty audience, and an `endTime` before `startTime`.
Add Bean Validation constraints and `@Valid`. Include a **cross-field** rule for
`endTime > startTime` (that needs a class-level constraint — look up
`@Constraint` on a type).

### 3.4 Move availability validation out of Jackson
Replace `AvailabilityPatternValidator` (a `JsonDeserializer`) with a proper
`ConstraintValidator` + custom annotation. Verify you now get a clean `400` with field-level
messages.
*Goal: you understand why the layer a check lives in determines the status code.*

### 3.5 Fix error responses
Stop returning `ex.getMessage()`. Add a correlation ID, log at `error`, return a reference.
Verify a deliberately triggered SQL error no longer leaks table names.

---

## Level 4 — Make it fast and correct

### 4.1 Kill the N+1
Rewrite `addEventFor` to load all participants in one query using `findByEmailIn`. Count
queries before and after. **While you're there, 2.6's fix falls out naturally** — notice
that.

### 4.2 Test the engine properly
Write a JUnit test class for `AvailabilityUtility` with **no Spring and no database**.
Cover: single user; two-user intersection; custom overriding default; unavailable day;
event blocking a slot; empty defaults; and the three bugs from Level 2.

Aim for ~15 tests. This is the highest-value hour in the whole tutorial — you'll finish it
understanding the algorithm better than the person who wrote it.

### 4.3 Rewrite the matrix as a difference array
Implement the O(buckets + 288) sweep from
[07](07-lld-availability-engine.md#complexity-and-the-version-youd-write-next).
**Your Level 4.2 tests must pass unchanged.** That's the point of the exercise: tests are
what make refactoring safe rather than terrifying.

### 4.4 Benchmark it
Generate 200 participants with random availability. Time both implementations. Report the
numbers.
*Goal: you learn to measure instead of guess — and you may find the "slow" version is fast
enough, which is also a valid finding.*

---

## Level 5 — Make it production-shaped

### 5.1 Externalise configuration
Convert `application.properties` to `${ENV_VAR:default}` form. Remove the hardcoded
`spring.profiles.active`. Add `application-dev.properties` and `application-prod.properties`.
Run the same JAR against two different databases without rebuilding.

### 5.2 Add Actuator
Expose health and metrics on a separate port. Stop MySQL and watch `/actuator/health` go
`DOWN`. Explain which of liveness/readiness should react to that, and what should happen.

### 5.3 Add a `.gitignore`
Then run `git status` after a build and confirm it's clean.

### 5.4 Containerise it
Write the multi-stage `Dockerfile` and `docker-compose.yml` from
[12](12-deploy-and-operate.md#docker-if-youd-rather). Get `docker compose up` to a working
API. Then **swap the `COPY pom.xml` and `COPY src` order**, rebuild after a one-line code
change, and compare build times. Explain what you saw.

### 5.5 Deploy it for real
Any cloud VM (free tier is fine). Systemd unit, non-root user, nginx in front, TLS via
Let's Encrypt, `server.address=127.0.0.1`. Then:
- `curl https://your-domain/user` from your laptop → works
- `curl http://your-server-ip:8080/user` → **must fail**, because you bound to loopback

If the second one succeeds, you have accidentally published an unauthenticated API. Fix it
before moving on. *Goal: this is the exercise that actually teaches `ip:port`.*

### 5.6 Break it on purpose
With it deployed: `kill -9` the process and watch systemd restart it. Stop MySQL and see
what users get. Fill the disk with logs. **Then fix each failure mode.**
*Goal: operating a service means knowing how it fails, not just how it works.*

---

## Level 6 — Design work

These have no single right answer. Write a **one-page design doc** for each — the writing
is the exercise. State the options, the trade-offs, your choice, and what would make you
change your mind.

### 6.1 Prevent double-booking
Address the TOCTOU race explicitly
([11](11-production-gaps.md#double-booking-is-not-prevented)). Also resolve the circular
dependency between `EventSchedulerService` and `AvailabilityService` that your design
creates.

### 6.2 Add real timezone support
Four layers deep. Cover schema, entity types, algorithm, API contract, migration of
existing rows, and DST for recurring availability. **Estimate the effort honestly.**

### 6.3 Add authentication
Pick a scheme, justify it, and say exactly which endpoints change. Then answer the awkward
part: **how do you migrate existing users** who currently authenticate with nothing?

### 6.4 Support 500-participant meetings
The README caps expectations at ~10. What breaks first at 500? What's the fix at each
level — algorithm, queries, API shape, caching? Which of these would you do *now* and which
would you wait for?
*Goal: distinguish real bottlenecks from imagined ones.*

### 6.5 Recurring meetings
"Every Tuesday at 10am until June." How do you store it — one row plus a rule, or expanded
rows? What happens when someone edits one occurrence? How does the availability engine
account for it? (This is genuinely hard. Real calendar systems use RFC 5545 RRULEs, and
still get it wrong.)

---

## Level 7 — The capstone

Pick **one** of 6.1–6.5 and implement it end to end:

- A design doc, reviewed by whoever gave you this repo, **before** you write code
- Migrations for any schema change
- Tests written alongside (not after)
- Backwards compatibility considered and stated
- A PR with a description explaining *why*, not just *what*
- Deployed to your VM from 5.5

**This is what the job is.** Not the code — the doc, the tests, the migration, the review,
the deploy, and being able to explain your reasoning to someone who disagrees.

---

## Self-assessment

You've got the value out of this tutorial when you can:

- [ ] Explain any line in `AvailabilityUtility` and why it's there
- [ ] Trace a request from socket to SQL and back without looking anything up
- [ ] Look at a new JPA entity and immediately spot the `@Data`/`toString`/fetch traps
- [ ] Read a `pom.xml` and know which dependencies are declared, transitive, and unused
- [ ] Recognise a TOCTOU race in code you're reviewing
- [ ] Deploy a Spring Boot app to a server and defend every line of the systemd unit
- [ ] Argue *both sides* of a design decision, then pick one and say what would change your mind
- [ ] Tell a bug from a deliberate scope cut — and know that shipping the second is fine
