# 12 — Deploy and Operate

Your brief says you have no understanding of running services on a server and exposing them
via `ip:port`. This file starts from first principles and ends with a running production
deployment.

---

## What `ip:port` actually means

When you run `java -jar app.jar` and see `Tomcat started on port 8080`, three things
happened:

1. The JVM asked the operating system for **TCP port 8080** (`bind()`).
2. The OS reserved it — **exclusively**. No other process can have 8080 at the same time.
   That's the `Address already in use` error.
3. The process now `listen()`s for connections arriving on that port.

An **IP address** identifies a *machine* on a network. A **port** identifies a *program* on
that machine. Together, `ip:port` is a full address for a conversation.

```
  192.168.1.42 : 8080
  └──────┬───┘   └─┬─┘
    which machine   which program on it
```

`localhost` / `127.0.0.1` is a special IP meaning "this same machine". Traffic to it never
touches a network card. That's why `curl http://localhost:8080` works from your laptop and
your colleague cannot reach it — they'd need your machine's real IP, and a network path to
it.

### The bind address matters as much as the port

A listening socket also has an **interface** it binds to:

| Bind address | Reachable from |
|---|---|
| `127.0.0.1` | this machine only |
| `0.0.0.0` | **every** network interface — including the public internet, if the machine has a public IP |
| `10.0.1.5` | just that one interface |

Spring Boot defaults to `0.0.0.0`. On a cloud VM with a public IP, **your app is on the
internet the moment it starts** — no auth, Swagger exposed, stack traces in error bodies.

```properties
server.address=127.0.0.1     # bind to loopback only; let nginx be the front door
server.port=8080
```

This one line is the difference between "an internal service behind a proxy" and "a public
API you didn't mean to publish."

### Ports below 1024 are privileged

Binding to 80 or 443 requires root on Linux. **Do not run your app as root to get port 80.**
If it's compromised, the attacker owns the machine. Instead: run the app unprivileged on
8080 and put a reverse proxy (which *is* designed to run privileged and drop) on 80/443.

### Useful commands

```bash
lsof -i :8080                       # what's using this port? (macOS/Linux)
ss -tlnp | grep 8080                # Linux: listening sockets
curl -v http://localhost:8080/user  # is it answering?
kill -9 $(lsof -t -i:8080)          # free a stuck port
```

---

## What you're actually deploying

```bash
mvn clean package
ls -lh target/CalendarApp-0.0.1-SNAPSHOT.jar
```

One file. Inside it: your compiled classes, every dependency JAR, and an embedded Tomcat.
That's `spring-boot-maven-plugin` producing a **fat JAR** (or "uber JAR").

```bash
unzip -l target/CalendarApp-0.0.1-SNAPSHOT.jar | head -30
```

You'll see `BOOT-INF/classes/` (your code), `BOOT-INF/lib/` (dependencies), and
`org/springframework/boot/loader/` — a custom classloader that can load JARs nested inside
a JAR, which normal Java can't do.

**Why this matters:** deployment is "copy one file, run `java -jar`". No application server
to install, no version-matching between your WAR and someone's Tomcat, no
"works-on-my-machine" from a different servlet container. The artifact you tested is
byte-for-byte the artifact you run.

**The one rule that makes this work:** build the artifact **once** and promote that same
file through dev → staging → prod. Never rebuild per environment — then you're testing one
binary and shipping another. All environment differences come from configuration, never
from the build.

---

## Configuration per environment

Spring Boot resolves properties in a fixed precedence order (highest wins):

```
1. Command-line arguments        --server.port=9090
2. Environment variables         SERVER_PORT=9090
3. application-{profile}.properties
4. application.properties        (inside the JAR)
```

Notice the **relaxed binding**: `SERVER_PORT` maps to `server.port`, and
`SPRING_DATASOURCE_URL` maps to `spring.datasource.url`. Uppercase, underscores for dots
and dashes. That's what makes environment variables a first-class config source.

### The pattern to use

Put placeholders in the committed file:

```properties
spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/calendar_db}
spring.datasource.username=${DB_USER:root}
spring.datasource.password=${DB_PASSWORD}
```

`${VAR:default}` means "use `VAR`, else this default". Note `DB_PASSWORD` has **no
default** — so the app **refuses to start** if it isn't supplied. That's deliberate:
failing at startup beats running with a wrong password and failing on the first request.

Then supply real values from the environment:

```bash
export DB_URL='jdbc:mysql://db.internal:3306/calendar_db'
export DB_USER='calendar_app'
export DB_PASSWORD='...'
java -jar app.jar --spring.profiles.active=prod
```

And delete `spring.profiles.active=dev` from the committed file — the active profile is an
environment decision, not an artifact decision.

### Secrets

**Never commit a secret.** Once it's in git history it's on every clone, forever; deleting
the line does nothing. If it happens: **rotate the credential**, don't just remove it.

Escalating options: environment variables (fine to start) → a file with `chmod 600` owned
by the service user → a secrets manager (AWS Secrets Manager, Vault) that supports rotation
and audit.

The database user this app connects as should also have **only** the privileges it needs:
`SELECT, INSERT, UPDATE, DELETE` on `calendar_db`. Not `DROP`, not `GRANT`, and not on
other schemas.

### Production properties worth setting

```properties
spring.jpa.show-sql=false                       # cost + noise
springdoc.swagger-ui.enabled=false              # don't publish your API map
server.address=127.0.0.1                        # behind the proxy only
server.shutdown=graceful                        # finish in-flight requests on SIGTERM
spring.lifecycle.timeout-per-shutdown-phase=30s
spring.jpa.open-in-view=false                   # don't hold a DB connection for the whole request
logging.level.root=INFO
management.endpoints.web.exposure.include=health,metrics,prometheus
management.server.port=8081                     # actuator on a port you don't route publicly
spring.datasource.hikari.maximum-pool-size=10
```

**`server.shutdown=graceful` deserves a moment.** By default a `SIGTERM` kills the process
immediately, dropping in-flight requests — so every deploy fails some user's request.
Graceful shutdown stops accepting new connections, finishes what's running, then exits.
One line, and your deploys stop being visible to users.

**On the connection pool:** HikariCP's default max is 10. That is usually *plenty* — a pool
is not a queue, and more connections than the database has cores makes throughput worse,
not better. If you're tempted to raise it, the real problem is almost always slow queries.
Note the interaction: Tomcat allows 200 concurrent requests and the pool allows 10
concurrent DB users, so 190 threads can end up waiting on the pool. That's the actual shape
of "the site is slow."

---

## Running it on a real server

### Naïve, and why it fails

```bash
java -jar app.jar &          # ✗ dies when you log out
nohup java -jar app.jar &    # ✗ survives logout, but not a crash or a reboot
```

You need something that (a) starts on boot, (b) restarts on crash, (c) captures logs, (d)
runs as an unprivileged user, (e) stops cleanly.

### systemd — the standard answer on Linux

`/etc/systemd/system/calendar-app.service`:

```ini
[Unit]
Description=Calendar Backend App
After=network.target mysql.service

[Service]
Type=simple
User=calendar
Group=calendar
WorkingDirectory=/opt/calendar-app

Environment="DB_URL=jdbc:mysql://localhost:3306/calendar_db"
Environment="DB_USER=calendar_app"
EnvironmentFile=/etc/calendar-app/secrets.env    # chmod 600, root-owned, DB_PASSWORD here

ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/calendar-app/app.jar --spring.profiles.active=prod

Restart=always
RestartSec=10
SuccessExitStatus=143          # 143 = 128+15 (SIGTERM) — a clean stop, not a failure
KillSignal=SIGTERM
TimeoutStopSec=40              # > the app's graceful-shutdown window

StandardOutput=journal
StandardError=journal
SyslogIdentifier=calendar-app

# Basic hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/calendar-app/logs

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /bin/false calendar        # a service account that cannot log in
sudo systemctl daemon-reload
sudo systemctl enable --now calendar-app
sudo systemctl status calendar-app
sudo journalctl -u calendar-app -f            # live logs
```

Line by line, what each thing buys you:

- **`User=calendar`** — an unprivileged service account. If the app is compromised, the
  attacker gets a user who can't log in and owns almost nothing. **Never run a service as
  root.**
- **`Restart=always` + `RestartSec=10`** — automatic recovery from crashes and OOM kills.
- **`After=mysql.service`** — ordering on boot (note: it's ordering, not a readiness check;
  the app must still tolerate the DB being briefly unavailable).
- **`SuccessExitStatus=143`** — without it, every clean `systemctl stop` is logged as a
  failure and pollutes your alerting.
- **`TimeoutStopSec=40` > graceful window** — systemd must wait longer than the app takes
  to drain, or it `SIGKILL`s mid-request and you've undone `server.shutdown=graceful`.
- **`WorkingDirectory`** — `logging.file.name=logs/application.log` is *relative*, so this
  is what decides where logs land ([01](01-setup-and-first-run.md#7-where-the-logs-go)).
- **`-Xmx1g`** — cap the heap. Without it the JVM sizes from total RAM, and on a small box
  the kernel's OOM killer eventually kills your process with no explanation in your logs.

### Reverse proxy (nginx)

```nginx
server {
    listen 443 ssl http2;
    server_name calendar.example.com;

    ssl_certificate     /etc/letsencrypt/live/calendar.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/calendar.example.com/privkey.pem;

    limit_req zone=api burst=20 nodelay;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
    }
}

server {
    listen 80;
    server_name calendar.example.com;
    return 301 https://$host$request_uri;
}
```

Why bother, when Tomcat could serve 443 itself:

1. **TLS termination** — certificates and renewal handled in one place, by software built
   for it.
2. **Port privileges** — nginx binds 443 as root and drops; your JVM never runs privileged.
3. **Rate limiting** — `limit_req` protects that expensive `WEEK?emails=...` endpoint.
4. **It absorbs slow clients.** A client on bad mobile network reading a response slowly
   ties up an nginx connection (cheap, event-driven) instead of a Tomcat thread (expensive,
   1 of 200).
5. **Multiple app instances** behind one address when you scale out.

`X-Forwarded-For` and `X-Forwarded-Proto` matter: without them your app sees every request
as coming from `127.0.0.1` over plain HTTP, which breaks logging, rate limiting, and any
redirect the app generates. Add `server.forward-headers-strategy=framework` so Spring
honours them.

---

## Health checks, and why you need them

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.probes.enabled=true
```

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}
```

Spring Boot's health check includes a **real database connectivity test**. That distinction
is the whole point: "the process is running" and "the process can serve requests" are
different questions, and only the second one matters.

- **Liveness** (`/actuator/health/liveness`) — is the process wedged? If not, restart it.
- **Readiness** (`/actuator/health/readiness`) — can it serve traffic *right now*? If not,
  take it out of the load balancer but **don't** restart it (the DB might just be briefly
  down; restarting won't help and removes capacity).

Confusing these two causes restart storms: the DB hiccups, every instance fails its
liveness check, everything restarts at once, and the DB is now hit by a thundering herd of
reconnections. Get the distinction right before you wire either one to an automated action.

---

## Docker, if you'd rather

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline           # cached layer: deps change rarely
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

Three things worth understanding rather than copying:

**Multi-stage build.** The Maven image (~500 MB, with a compiler and a dependency cache)
builds; the final image contains only a JRE and your JAR (~150 MB). Smaller images pull
faster and have a far smaller attack surface — no compiler, no shell utilities, nothing for
an attacker to use.

**`COPY pom.xml` before `COPY src`.** Docker caches layers. Dependencies change rarely and
source changes constantly, so downloading dependencies in an earlier layer means a code
change doesn't re-download the internet. This one ordering trick turns a 3-minute build
into a 20-second one.

**`USER app`.** Containers run as root by default. A container is not a security boundary
the way a VM is — root in the container is uncomfortably close to root on the host. Always
add a non-root user.

**`-XX:MaxRAMPercentage=75`** instead of `-Xmx`: the JVM sizes the heap from the
*container's* memory limit rather than the host's. Without it, a JVM in a 512 MB container
on a 64 GB host may size its heap for 64 GB and get OOM-killed by the kernel with no Java
stack trace at all. This is one of the most confusing failure modes in containerised Java.

```yaml
# docker-compose.yml — local dev with a real MySQL
services:
  db:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: calendar_db
    ports: ["3306:3306"]
    volumes:
      - ./db_schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - dbdata:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      retries: 10

  app:
    build: .
    ports: ["8080:8080"]
    environment:
      DB_URL: jdbc:mysql://db:3306/calendar_db
      DB_USER: root
      DB_PASSWORD: root
    depends_on:
      db: { condition: service_healthy }

volumes:
  dbdata:
```

Note `jdbc:mysql://db:3306` — inside a compose network, the service name **is** the
hostname. And `depends_on: condition: service_healthy` waits for MySQL to actually accept
connections, not merely for its container to exist. Plain `depends_on` doesn't, which is
why "it works the second time I run it" is such a common compose complaint.

---

## Scaling, when you get there

The app is **stateless** — no in-memory session, no local cache, no server-side state. That
is the property that makes everything below possible, and it's worth actively protecting:
the day someone adds a `static Map` cache, horizontal scaling silently breaks.

**Vertical first** (bigger machine): simplest, no code changes, and usually the right first
move. It ends at the size of the biggest machine you can buy.

**Horizontal** (more instances behind nginx/an ALB):

```
                    ┌──────────────┐
      Internet ────▶│ Load balancer│
                    └──┬────┬───┬──┘
             ┌─────────┘    │   └─────────┐
        ┌────▼───┐    ┌─────▼──┐    ┌─────▼──┐
        │ app :8080   │ app :8080   │ app :8080
        └────┬───┘    └─────┬──┘    └─────┬──┘
             └──────────────┼─────────────┘
                    ┌───────▼────────┐
                    │  MySQL primary │
                    └────────────────┘
```

Works today with zero code changes. But note what it does **not** fix: every instance still
talks to one database, so the DB becomes the bottleneck. Next steps in order: read replicas
for the availability queries (they're read-heavy), then caching, then sharding — and by
then you have a very different product.

**Where this app would actually hurt first:** `GET /availability/{date}/WEEK` with many
participants — it's CPU-bound *and* runs four queries. It's the first thing to cache
(the README already anticipates this) and the first thing to rate-limit.

---

## The deployment checklist

Before any code reaches a public server:

- [ ] `server.address=127.0.0.1`; only the reverse proxy is public
- [ ] TLS terminated; HTTP redirects to HTTPS
- [ ] Secrets from environment/secret store; nothing sensitive in git
- [ ] The app runs as a non-root, non-login user
- [ ] systemd (or an orchestrator) restarts it on crash and starts it on boot
- [ ] `server.shutdown=graceful`, with `TimeoutStopSec` longer than the drain window
- [ ] Heap capped (`-Xmx` or `MaxRAMPercentage`)
- [ ] Health endpoint wired to the load balancer; liveness ≠ readiness
- [ ] Logs rotated (`logrotate` or journald) so the disk can't fill
- [ ] `show-sql=false`, Swagger disabled or authenticated
- [ ] Error responses carry a reference ID, not a stack trace
- [ ] Schema changes applied by migrations, not by hand
- [ ] Database backups exist **and a restore has been tested** — an untested backup is a
      rumour, not a backup
- [ ] You can answer "is it up?" and "how many 500s in the last hour?" without SSHing in
- [ ] Rate limiting on the expensive endpoints
- [ ] A rollback plan: the previous JAR is still on disk and one `systemctl restart` away

## Checkpoint

- [ ] Explain `ip:port` and why binding `0.0.0.0` on a cloud VM is dangerous
- [ ] Explain why you don't run the app as root to serve port 443
- [ ] Explain why the artifact is built once and promoted, not rebuilt per environment
- [ ] Explain the difference between liveness and readiness, and the restart-storm failure
- [ ] Explain why `COPY pom.xml` comes before `COPY src` in the Dockerfile
