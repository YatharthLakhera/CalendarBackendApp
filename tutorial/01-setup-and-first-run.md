# 01 — Setup and First Run

Goal: this app running on your laptop, connected to a real MySQL, answering a `curl`.
Nothing else in this tutorial makes sense until that works.

## 1. What you need installed

| Thing | Version | Check with |
|---|---|---|
| JDK | 17 (project targets `<java.version>17</java.version>` in `pom.xml`) | `java -version` |
| Maven | 3.x | `mvn -version` |
| MySQL Server | 8.x | `mysql --version` |

### Why exactly Java 17?

`pom.xml` sets `<java.version>17</java.version>`, and the parent is
`spring-boot-starter-parent:3.3.5`. Spring Boot 3 requires **Java 17 as a minimum** —
it dropped Java 8/11 support entirely, and it migrated from `javax.*` to `jakarta.*`
package names. That's why you see `jakarta.persistence.*` and `jakarta.validation.*`
imports in this codebase and `javax.persistence.*` in every 5-year-old tutorial you'll
find online. **If you paste code from an old blog post, the imports will not compile.**
That is the single most common time-waster for someone new to Spring Boot 3.

Running on Java 21 will also work. Running on Java 11 will not compile.

### ⚠️ The Maven wrapper in this repo is broken

The repo contains `mvnw` and `mvnw.cmd`, but **not** the `.mvn/wrapper/` directory they
need. Try it:

```bash
sh mvnw -version
# mvnw: line 114: mvnw/.mvn/wrapper/maven-wrapper.properties: Not a directory
```

The whole point of a Maven wrapper is that a new developer can build the project without
installing Maven, using the exact Maven version the project expects. It works by reading
`.mvn/wrapper/maven-wrapper.properties` for a `distributionUrl` and downloading that
Maven. Here that file was never committed, so the wrapper is dead weight.

**Use your locally installed `mvn` instead.** Then read
[11-production-gaps.md](11-production-gaps.md#version-control-hygiene) for the lesson:
knowing *what belongs in version control* is a real skill, and this is what it looks
like when you get it slightly wrong.

## 2. Create the database

The schema is **not** created by the application. Look at `src/main/resources/application.properties`:

```properties
spring.jpa.hibernate.ddl-auto=none
```

`ddl-auto=none` means **Hibernate will not create, alter, or drop a single table**. It
assumes the schema already exists and matches your entities. If it doesn't match, you
get a runtime error on the first query, not at startup.

You may have seen `ddl-auto=update` in tutorials — it makes Hibernate diff your entities
against the DB and issue `ALTER TABLE` automatically. It is wonderful for a toy project
and **actively dangerous in production**: it will happily add columns you didn't intend,
never removes anything, can lock large tables, and gives you no record of what changed.
Choosing `none` here is the correct production instinct. See
[11-production-gaps.md](11-production-gaps.md#no-schema-migrations) for what you'd use
*instead* (Flyway/Liquibase) — because `none` alone means "somebody runs SQL by hand",
which is its own problem.

Run the schema:

```bash
mysql -u root -p < db_schema.sql
```

That creates the `calendar_db` database and four tables. Read
[04-db-schema-and-orm.md](04-db-schema-and-orm.md) before you run it if you want to
understand what you're creating.

> Note: `db_schema.sql` exists **twice** — at the repo root and at
> `src/main/resources/db_schema.sql`. They are identical today. Two copies of the same
> truth is a bug waiting to happen; only one of them will get updated next time.

## 3. Configure the connection

`application.properties` ships with placeholders, not real credentials:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/your_database_name
spring.datasource.username=your_username
spring.datasource.password=your_password
```

**This is deliberate and correct.** Real credentials must never be committed to git —
once a password is in git history, it is in git history forever, on every laptop that
ever cloned the repo. Placeholders are the minimum acceptable practice.

To run locally, point it at your database. For now, edit the file:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/calendar_db
spring.datasource.username=root
spring.datasource.password=<your local password>
```

**Do not commit that edit.** Better yet, don't edit the file at all — override at
launch instead:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:mysql://localhost:3306/calendar_db --spring.datasource.username=root --spring.datasource.password=secret"
```

Spring Boot resolves configuration from many sources in a fixed priority order, roughly:
command-line arguments **beat** environment variables **beat**
`application-{profile}.properties` **beat** `application.properties`. That ordering is
the mechanism behind every "same JAR, different environment" deployment you'll ever do.
[12-deploy-and-operate.md](12-deploy-and-operate.md#configuration-per-environment) covers
how this works on a real server.

### The unused `dev` profile

```properties
spring.profiles.active=dev
```

This activates a profile named `dev` — and then the project contains **no**
`application-dev.properties` file, so the profile does nothing at all. It's a placeholder
for a pattern that was never finished. The intended pattern:

```
application.properties            # shared defaults
application-dev.properties        # local DB, verbose logging
application-prod.properties       # real DB, INFO logging, no SQL echo
```

...and then `-Dspring.profiles.active=prod` on the server. Note also that hardcoding
`spring.profiles.active=dev` in the committed base file is backwards — the active
profile is an *environment* decision, so it belongs in the launch command, not in the
JAR.

## 4. Build and run

```bash
mvn clean package        # compiles, runs tests, produces target/CalendarApp-0.0.1-SNAPSHOT.jar
mvn spring-boot:run      # or just run it directly
```

Or run the JAR the way a server would:

```bash
java -jar target/CalendarApp-0.0.1-SNAPSHOT.jar
```

`mvn clean package` produces an **executable fat JAR** — it contains your code *and*
every dependency *and* an embedded Tomcat web server. That's what
`spring-boot-maven-plugin` in `pom.xml` does. This is why the deployment story for a
Spring Boot app is "copy one file to a server and run `java -jar`", with no Tomcat to
install and no WAR to deploy. That was genuinely revolutionary in 2014 and is now just
how it's done.

You should see something like:

```
Tomcat started on port 8080 (http) with context path '/'
Started CalendarAppApplication in 3.2 seconds
```

`server.port=8080` in `application.properties` chose that port. That number is the
entire content of "exposing a service on `ip:port`" — see
[12-deploy-and-operate.md](12-deploy-and-operate.md#what-ipport-actually-means).

## 5. Prove it works

```bash
# Create a user
curl -s -X POST http://localhost:8080/user \
  -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com"}'
```

Expected:

```json
{"name":"Asha","email":"asha@example.com","id":"1728dfe4-a197-47bf-9c4c-c80011c58f9e"}
```

Copy that `id` — almost every other endpoint needs it in a `userId` **header**.

```bash
curl -s http://localhost:8080/user -H 'userId: 1728dfe4-a197-47bf-9c4c-c80011c58f9e'
```

Full walkthrough of all three features: [05-user-flows.md](05-user-flows.md).

## 6. The two tools you'll live in

### Swagger UI

```
http://localhost:8080/swagger-ui/index.html
```

`springdoc-openapi-starter-webmvc-ui` in `pom.xml` scans your controllers at startup and
generates an OpenAPI spec plus a browsable UI. You didn't write a line of config for it —
that's Spring Boot **auto-configuration**: a JAR on the classpath registers beans by
itself.

Two things to understand about it here:

1. It reflects *only* what it can see from method signatures and annotations. Since this
   project adds no `@Operation`/`@Schema` annotations, the descriptions are empty and the
   documented error responses are wrong (everything claims `200`). Swagger UI shows you
   the *shape*, not the *contract*.
2. It is exposed with no authentication. On a real internet-facing server, a public
   Swagger UI is a free map of your API for an attacker. See
   [11-production-gaps.md](11-production-gaps.md#swagger-is-publicly-exposed).

### The SQL log

```properties
spring.jpa.show-sql=true
```

Every SQL statement Hibernate issues gets printed. **Turn this on and watch it while you
use the app.** It is the fastest way to learn what an ORM is actually doing on your
behalf — including the extra queries you didn't ask for. In
[07-lld-availability-engine.md](07-lld-availability-engine.md) and
[11-production-gaps.md](11-production-gaps.md#the-n1-query-problem) you'll use this to
find a real performance problem.

⚠️ `show-sql=true` writes to stdout (not the logger), has no parameter values, and costs
real throughput. In production you'd turn it off and use
`logging.level.org.hibernate.SQL=DEBUG` instead when you need it.

## 7. Where the logs go

```properties
logging.file.name=logs/application.log
logging.level.root=INFO
```

Logs are written to `logs/application.log` **relative to the process's working
directory** — so where they land depends on where you started the app from, not where
the JAR lives. That surprises people the first time they deploy. (Also note
`logging.file.path` is set alongside `logging.file.name`; when both are present Spring
Boot uses `logging.file.name` and ignores the other, so that line does nothing.)

There's no `logs/` entry in a `.gitignore` — because there is no `.gitignore` at all in
this repo. Run the app once from the repo root and `git status` will show you untracked
log files. Ask yourself what else is missing from that file. (Answer: `target/`, IDE
folders, and any local config.)

## Checkpoint

You are done with this file when you can:

- [ ] Start the app and see `Started CalendarAppApplication`
- [ ] Create a user via `curl` and get a UUID back
- [ ] Find that user's row in MySQL (hint: the ID is stored as binary — see
      [04-db-schema-and-orm.md](04-db-schema-and-orm.md#why-you-cant-just-select-by-uuid))
- [ ] Explain why `ddl-auto=none` is there
- [ ] Explain what a fat JAR is and why it matters for deployment
