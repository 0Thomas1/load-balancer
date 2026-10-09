# Docker Single-Container Packaging — Design Spec

**Date:** 2026-10-08
**Status:** Approved for review
**Path:** Architectural (new deployment subsystem)

## Purpose

Put the whole load-balancer sandbox behind one Docker image so that anyone with
Docker can run the full dashboard with a single `docker compose up` — no
Maven, Node, or JDK installed. The container packages the existing
Spring Boot backend and the built React SPA into one artifact and serves both
on `:8080`, exactly the way the app already serves itself today
(`WebConfig` + `SpaFallbackResolver`).

**Success criteria:** from a clean checkout, `docker compose up -d` builds and
starts the sandbox, `http://localhost:8080` renders the live dashboard
(SPA + `/api` + `/ws/metrics` all working), and `docker compose down` removes
it cleanly — with zero changes to backend or frontend source.

## Scope

### In scope
- `Dockerfile` — multi-stage build (frontend bundle, backend jar, runtime image)
- `.dockerignore` — keeps the build context lean
- `docker-compose.yml` — one `app` service (build, port, healthcheck, env)
- `Makefile` — `docker-build`, `docker-up`, `docker-down` convenience targets
- `README.md` — short "Run with Docker" section

### Out of scope
- CI workflow (tests remain `make test-backend` / `make test-frontend`)
- A separate frontend web server (nginx, etc.) — the backend already serves the SPA
- Dev-mode containers with hot reload — the local `make dev-backend` /
  `make dev-frontend` workflow stays exactly as it is
- Image registry / publishing — the image is built and used locally
- Any change to backend or frontend source code

## Decisions

Recorded from the brainstorming session; the user selected the highlighted options.

1. **Single container.** One multi-stage image builds the Vite bundle and the
   Spring Boot jar, then a small JRE image runs the jar and serves the built
   SPA in-process. This reuses the app's existing production-style serving and
   adds only one service to compose.
2. **Local containerization only, no CI.** The existing test suites already
   cover the app; the image build intentionally uses `-DskipTests` so builds are
   fast and reproducible, and tests keep running through the existing Make
   targets.
3. **Runtime JDK 17** to match the pom's declared Java target
   (`<java.version>17</java.version>`), as `eclipse-temurin:17-jre-alpine`.
   (Local dev runs JDK 19; the container is pinned to the supported target.)
4. **Node 22** (`node:22-alpine`) for the frontend build — Vite 5 requires
   Node ≥ 18; 22 is an LTS line.
5. **Maven 3.9 / Temurin 17** (`maven:3.9-eclipse-temurin-17`) as the backend
   builder.
6. **Non-root runtime user.** The container runs the jar as an unprivileged
   user rather than root.

## Architecture

One image, three build stages, one runtime stage:

```
Dockerfile (build context = repo root)
  stage 1  frontend-build   node:22-alpine
           npm ci → npm run build        → /build/frontend/dist
  stage 2  backend-build    maven:3.9-eclipse-temurin-17
           ./mvnw -B -DskipTests package  → /build/backend/target/*.jar
  stage 3  runtime          eclipse-temurin:17-jre-alpine
           copy boot jar → /opt/app/app.jar
           copy dist     → /opt/app/frontend/dist
           non-root user, EXPOSE 8080
           ENTRYPOINT java -jar app.jar --app.frontend-dist=/opt/app/frontend/dist
```

Routing at runtime is identical to today's production-style serving:
`GET /` and unknown paths → `index.html` (SPA fallback), `/api/*` → REST
controllers, `/ws/metrics` → WebSocket handler. No source changes required.

## Files

### `Dockerfile` (repo root)

- Stage 1: `node:22-alpine`; copy `frontend/package.json` and
  `frontend/package-lock.json` first, `npm ci`, then copy the remaining
  `frontend/` source and `npm run build`. Output: `dist/` inside the stage.
- Stage 2: `maven:3.9-eclipse-temurin-17`; copy `backend/pom.xml` first (so the
  dependency layer caches), then `backend/src`; `mvn -B -DskipTests package`.
  The `spring-boot-maven-plugin` repackages the jar; the boot jar is copied over
  with `target/*.jar` so a future version bump does not force an edit here
  (the `.jar.original` side artifact is not matched by that glob).
- Stage 3: `eclipse-temurin:17-jre-alpine`; `WORKDIR /opt/app`; copy the jar as
  `app.jar` and the built `dist/` to `./frontend/dist`; create a non-root user
  (`addgroup`/`adduser`), `chown` `/opt/app`; `USER` that user; `EXPOSE 8080`;
  exec-form `ENTRYPOINT` with the baked `--app.frontend-dist` flag.

### `.dockerignore` (repo root)

Excludes `.git`, `**/node_modules`, `frontend/dist` (rebuilt in-image),
`backend/target`, `docs`, `.DS_Store`, and editor/OS droppings. Keeping the
stale local `dist/` (and `target/`) out of the context is what keeps the build
context small and deterministic.

### `docker-compose.yml` (repo root)

One service, `app`:

- `build: .` with `image: load-balancer-sandbox:local`
- `ports: ["8080:8080"]`
- `environment`: `APP_DEMO_AUTOSTARTTRAFFIC` passthrough from the host,
  defaulting to `true` (Spring relaxed binding maps it to
  `app.demo.autoStartTraffic`, and the property already defaults to `true`, so
  the sandbox always boots with traffic either way).
- `healthcheck`: busybox `wget -q -O /dev/null http://localhost:8080/api/backends`
  (the JRE alpine image ships busybox), interval 10s, timeout 3s, retries 5,
  start_period 20s.
- `restart: unless-stopped`.
- No `version:` key (legacy compose v1 is long gone).

### `Makefile` additions

```make
docker-build:
	docker build -t load-balancer-sandbox:local .

docker-up:
	docker compose up -d --build

docker-down:
	docker compose down
```

Tab-indented to match the existing Makefile, placed after the existing targets.

### `README.md`

A short "Run with Docker" subsection after the current build/serving section:
`make docker-up`, then `http://localhost:8080`; `make docker-down` to stop.
Notes that the image is built locally and requires no Maven/Node/JDK.

## Behavior & configurability

- The image bakes `--app.frontend-dist=/opt/app/frontend/dist`, which makes
  `WebConfig` (and its `SpaRootController` condition) activate inside the
  container — SPA + REST + WebSocket on a single port.
- `app.demo.autoStartTraffic` is configurable via the compose environment but
  defaults to `true`, so the dashboard streams traffic out of the box.
- The image build skips test execution on purpose (`-DskipTests`); the repo's
  test workflows are unchanged.

## Error handling & ops

- Any stage failure (npm, maven, copy) fails the whole build — no partial image
  is produced.
- Compose healthcheck surfaces a non-serving container as unhealthy; logs go to
  stdout and are visible with `docker compose logs`.
- `docker compose down` removes the container; `docker compose down -v` is not
  needed (no volumes are declared — state is in-memory by design).

## Testing & verification

Build and smoke-test the container end-to-end before calling this done:

1. `docker compose config` — validates the compose file.
2. `docker build -t load-balancer-sandbox:local .` — the full multi-stage build
   succeeds.
3. `docker compose up -d` — container starts and passes its healthcheck
   (`docker compose ps` shows `healthy`).
4. `curl -s localhost:8080/` returns the SPA shell; the referenced
   `assets/index-*.{js,css}` load (200).
5. `curl -s localhost:8080/api/backends` returns JSON with the four seeded
   backends.
6. A short Node WebSocket probe on `/ws/metrics` shows snapshots streaming.
7. `docker compose down` stops and removes the container cleanly.

## Out of scope (future)

- CI build/push of the image
- Multi-service layouts (separate nginx) if the dashboard ever outgrows the
  embedded serving
- Containerized dev environment with hot reload