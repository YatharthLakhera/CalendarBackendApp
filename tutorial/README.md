# CalendarBackendApp — Intern Tutorial

Welcome. This folder is a guided tour of a real, small, production-shaped Spring Boot
backend. By the end you should be able to open any file in `src/main/java` and explain
**why every line is there**, not just what it does.

## Who this is written for

You already know:

- Core Java (classes, interfaces, generics, collections, streams — at least by sight)
- Basic Spring Boot (`@RestController`, `@Service`, `@Autowired`)
- Basic Hibernate/JPA (`@Entity`, `@Id`, repositories)
- Basic MySQL (tables, keys, joins)

You have **not** yet:

- Worked on a system that real users hit
- Deployed anything to a server and exposed it on `ip:port`
- Had to argue about a design decision in a code review
- Debugged a bug that only appears with two users instead of one

That gap is exactly what this tutorial fills.

## What this tutorial deliberately does NOT do

It does not explain what `@RestController` means, or what `@Autowired` does, or the
difference between `@GetMapping` and `@PostMapping`. Google, the Spring docs, and
Baeldung do that better and faster than any document in a repo.

What Google **cannot** tell you is:

- Why *this* project stores UUIDs as `binary(16)` and what that costs you
- Why `AvailabilityUtility` builds a 288-column integer matrix instead of comparing time ranges
- Why `UserService.getUserFor(...)` is `protected` and not `public`
- Which library in `pom.xml` half the codebase depends on **without it being declared**
- Which lines in this repo are bugs, and how you'd have caught them before a user did

That's what's in here.

## How to work through it

Read in order. Each file assumes the previous ones. Budget roughly 1–2 focused days
for a first pass, then come back to files 07–12 when you actually have to change code.

| # | File | What you get out of it |
|---|------|------------------------|
| 01 | [Setup and first run](01-setup-and-first-run.md) | The app running on your machine, talking to MySQL, answering a `curl` |
| 02 | [Request lifecycle](02-request-lifecycle.md) | What actually happens between `curl` and a MySQL row |
| 03 | [API reference](03-api-reference.md) | Every endpoint, its exact signature, and its sharp edges |
| 04 | [Database schema and ORM mapping](04-db-schema-and-orm.md) | Tables, keys, and how each Java field lands in a column |
| 05 | [User flows](05-user-flows.md) | The three features end to end, as real API calls you can paste |
| 06 | [High level design (HLD)](06-hld.md) | The boxes, the arrows, and why the layers exist |
| 07 | [Low level design — the availability engine](07-lld-availability-engine.md) | The hardest 200 lines in the repo, line by line |
| 08 | [Code walkthrough](08-code-walkthrough.md) | Every package and file, and why it exists |
| 09 | [Java, Lombok, Jackson & Hibernate gaps](09-java-gaps.md) | The project-specific traps you'd never think to search for |
| 10 | [Design principles in this codebase](10-design-principles.md) | SOLID with examples *and counter-examples* taken from these files |
| 11 | [Production gaps](11-production-gaps.md) | Everything that would break or bite if you shipped this today |
| 12 | [Deploy and operate](12-deploy-and-operate.md) | JAR, ports, servers, `ip:port`, systemd, nginx, Docker, logs, secrets |
| 13 | [Exercises](13-exercises.md) | Graded tasks — including a bug hunt with real bugs |
| 14 | [Glossary](14-glossary.md) | Terms this tutorial uses without apologising |

## The one rule while reading

**Run everything.** Do not read `07-lld-availability-engine.md` and nod along. Set two
users' availability, book a meeting between them, hit the availability endpoint, and
watch the slot disappear. A backend engineer who has only read code is a backend
engineer who has not yet learned anything.

## A note on the bugs

This tutorial points out several genuine bugs in the code you are about to inherit.
That is not a criticism of the author — it is the single most useful thing a tutorial
can do for you. Every codebase you will ever join has bugs like these. The skill worth
building is *noticing* them, and knowing which ones matter.

Bugs are marked with a 🐞. Design gaps (deliberate scope cuts, not mistakes) are
marked with a ⚠️. Learn to tell the two apart — that distinction is most of what
"engineering judgement" means.
