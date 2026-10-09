# Docker Single-Container Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the whole load-balancer sandbox (Spring Boot backend + built React SPA) into one locally-built Docker image served on `:8080`.

**Architecture:** A single multi-stage `Dockerfile` at the repo root builds the Vite bundle (Node 22), builds the Spring Boot jar (Maven/Temurin 17, `-DskipTests`), then a JRE 17 Alpine runtime runs the jar as a non-root user with the built SPA beside it — reusing the app's existing `WebConfig` static serving. A one-service `docker-compose.yml` is the entry point; `Makefile` targets and a README section complete the story.

**Tech Stack:** Docker (BuildKit), `node:22-alpine`, `maven:3.9-eclipse-temurin-17`, `eclipse-temurin:17-jre-alpine`, Vite 5, Spring Boot 3.2.5, Docker Compose v2.

**Spec:** `docs/superpowers/specs/2026-10-08-docker-single-container-design.md`

## Global Constraints

- Docker daemon must be running. macOS: launch Docker Desktop (`open -a Docker`) and wait until `docker info` succeeds before any `docker` command.
- Builds need outbound network access (npm registry + Maven Central).
- Host port `8080` must be free for compose; the Task 1 bare smoke test uses host port `8090` instead.
- Exact image tags: frontend builder `node:22-alpine`, backend builder `maven:3.9-eclipse-temurin-17`, runtime `eclipse-temurin:17-jre-alpine`.
- Backend builds with `mvn -B -DskipTests package`; tests stay out of the image (spec decision 2).
- Runtime layout: jar at `/opt/app/app.jar`, SPA at `/opt/app/frontend/dist`, entrypoint flag `--app.frontend-dist=/opt/app/frontend/dist`; `EXPOSE 8080`; non-root user (`addgroup -S appgroup && adduser -S appuser -G appgroup`, `USER appuser`).
- Image is tagged `load-balancer-sandbox:local` everywhere (bare build, compose `image:`, Makefile).
- `.dockerignore` must exclude at least: `.git`, `**/node_modules`, `frontend/dist`, `backend/target`, `docs`, `.DS_Store`.
- Compose: service named `app`, `build: .`, `ports: ["8080:8080"]`, env `APP_DEMO_AUTOSTARTTRAFFIC` defaulting to `true`, healthcheck `wget -q -O /dev/null http://localhost:8080/api/backends` (interval 10s, timeout 3s, retries 5, start_period 20s), `restart: unless-stopped`, no `version:` key.
- Zero changes to `backend/src` or `frontend/src`. Commit messages follow the repo's conventional-commit style.

## Review Focus

- **Non-root execution:** a container that runs the app as root is a regression for anyone adopting it. Pinned by Task 1's image-inspect check.
- **Healthcheck must actually work on the runtime image:** only busybox tools exist on `-alpine`; `wget` must be present for compose to ever report `healthy`. Pinned by Task 2's `docker compose ps` wait.
- **A stopped Docker daemon must not cause a confusing mid-task failure:** every task's first step is a daemon readiness check. Pinned in Task 1 step 2.
- **Port collision with a locally running backend:** smoke tests use `8090`, compose uses the spec'd `8080`; Task 2 verifies `8080` is free before `up`.
- **Fresh bundle must be served, not a stale local `dist`:** the SPA root and its hashed `assets/index-*.js` must return 200 from the container. Pinned in Task 1 steps 5–6.

---

### Task 1: `Dockerfile` + `.dockerignore`

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

**Interfaces:**
- Produces: image `load-balancer-sandbox:local`; container entry `java -jar app.jar --app.frontend-dist=/opt/app/frontend/dist` on `:8080`. Tasks 2 and 3 consume this image via `build: .`.

- [ ] **Step 1: Ensure the Docker daemon is up**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: a version string. If it fails, start Docker Desktop (`open -a Docker`) and poll `docker info` (up to ~120s) before continuing.

- [ ] **Step 2: Write `.dockerignore` at the repo root**

```gitignore
.git
**/node_modules
frontend/dist
backend/target
docs
.DS_Store
```

- [ ] **Step 3: Write the `Dockerfile` at the repo root**

```dockerfile
# syntax=docker/dockerfile:1

FROM node:22-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /opt/app
COPY --from=backend-build /build/backend/target/*.jar app.jar
COPY --from=frontend-build /build/frontend/dist ./frontend/dist
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && chown -R appuser:appgroup /opt/app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--app.frontend-dist=/opt/app/frontend/dist"]
```

- [ ] **Step 4: Build the image**

Run: `docker build -t load-balancer-sandbox:local .`
Expected: BUILD SUCCESS; all three stages complete. The `target/*.jar` glob matches only the repackaged boot jar, never the `.jar.original` side artifact.

- [ ] **Step 5: Start the smoke container and wait for the API**

Run:
```bash
docker run -d --rm --name lb-sandbox-smoke -p 8090:8080 load-balancer-sandbox:local
for i in $(seq 1 30); do curl -sf http://localhost:8090/api/backends >/dev/null && break; sleep 1; done
```
Expected: the loop exits with `curl` succeeding (Spring Boot answer).

- [ ] **Step 6: Verify the runtime image runs as a non-root user**

Run: `docker image inspect load-balancer-sandbox:local --format '{{.Config.User}}'`
Expected: `appuser` (not empty, not `root`).

- [ ] **Step 7: Verify the SPA shell and its hashed assets are served**

Run:
```bash
curl -s http://localhost:8090/ | grep -o 'assets/index-[^"]*\.\(js\|css\)'
curl -sf -o /dev/null -w '%{http_code}\n' "http://localhost:8090/<first asset path>"
```
Expected: the `index.html` references `assets/index-*.js`/`.css`, and fetching that asset returns `200`.

- [ ] **Step 8: Verify the REST API and the WebSocket stream**

Run:
```bash
curl -s http://localhost:8090/api/backends
node -e "const ws=new WebSocket('ws://localhost:8090/ws/metrics');let n=0;ws.onmessage=()=>n++;ws.onopen=()=>console.log('OPEN');setTimeout(()=>{console.log('msgs='+n);process.exit(n>0?0:1)},5000)"
```
Expected: backends JSON with the four seeded backends; probe prints `OPEN` and `msgs=` with a positive count (iterated snapshots), exit code 0.

- [ ] **Step 9: Stop the smoke container**

Run: `docker stop lb-sandbox-smoke`
Expected: container exits and is auto-removed (`--rm`); host port 8090 released.

- [ ] **Step 10: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "feat(docker): single-container image that serves the whole sandbox"
```

### Task 2: `docker-compose.yml`

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: `load-balancer-sandbox:local` via `build: .`; the Task 1 image or its cache.
- Produces: `docker compose up -d` / `docker compose down` lifecycle.

- [ ] **Step 1: Confirm the daemon is up and `8080` is free**

Run: `docker info --format '{{.ServerVersion}}'` and `lsof -ti tcp:8080`
Expected: a version string and an empty port list. Start Docker Desktop (as in Task 1) if needed; this task cannot precede Task 1's committed files.

- [ ] **Step 2: Write `docker-compose.yml` at the repo root**

```yaml
services:
  app:
    build: .
    image: load-balancer-sandbox:local
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      APP_DEMO_AUTOSTARTTRAFFIC: ${APP_DEMO_AUTOSTARTTRAFFIC:-true}
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/api/backends"]
      interval: 10s
      timeout: 3s
      retries: 5
      start_period: 20s
```

- [ ] **Step 3: Validate the compose file**

Run: `docker compose config`
Expected: prints the resolved service with `image: load-balancer-sandbox:local`, the healthcheck, and `8080:8080`; no warnings/errors.

- [ ] **Step 4: Bring the stack up**

Run: `docker compose up -d --build`
Expected: build reuses cached layers, container `app` starts.

- [ ] **Step 5: Wait for the healthcheck to pass**

Run: `docker compose ps`
Expected: the `app` service reports `healthy` within ~30s. A service in `starting` forever means the busybox `wget` healthcheck is broken — fix the `test:` line, don't skip it.

- [ ] **Step 6: Verify the full stack on `:8080`**

Run (same shape as Task 1 steps 7–8, but on port 8080 and against `localhost:8080`):
```bash
curl -s http://localhost:8080/ | grep -o 'assets/index-[^"]*\.\(js\|css\)'
curl -s http://localhost:8080/api/backends
node -e "const ws=new WebSocket('ws://localhost:8080/ws/metrics');let n=0;ws.onmessage=()=>n++;ws.onopen=()=>console.log('OPEN');setTimeout(()=>{console.log('msgs='+n);process.exit(n>0?0:1)},5000)"
```
Expected: SPA shell + hashed asset, backends JSON, and a positive WebSocket message count — proving traffic auto-started under the default env value.

- [ ] **Step 7: Tear down cleanly**

Run: `docker compose down`
Expected: container removed; `docker compose ps` lists nothing; `lsof -ti tcp:8080` is empty.

- [ ] **Step 8: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): one-command compose up/down for the sandbox"
```

### Task 3: `Makefile` targets + README

**Files:**
- Modify: `Makefile` (append after the existing build-frontend target)
- Modify: `README.md` (add a "Run with Docker" section after the "Building for production-style serving" section)

**Interfaces:**
- Consumes: Task 2's `docker compose up -d --build` / `down` flow.

- [ ] **Step 1: Append the Docker targets to the `Makefile`**

Tab-indented exactly like the existing targets (`.PHONY` line included in the same block):

```make
## Run the whole sandbox as one Docker container
.PHONY: docker-build docker-up docker-down
docker-build:
	docker build -t load-balancer-sandbox:local .

docker-up:
	docker compose up -d --build

docker-down:
	docker compose down
```

- [ ] **Step 2: Exercise the targets**

Run: `make docker-up`, then `docker compose ps` (expect `healthy`), then `curl -sf -o /dev/null http://localhost:8080/` (expect 200), then `make docker-down`.
Expected: the compose lifecycle works through the Makefile with no manual steps.

- [ ] **Step 3: Add the "Run with Docker" section to the README**

Insert after the "Building for production-style serving" section:

````markdown
## Run with Docker

The whole sandbox (backend + dashboard) ships as a single image, so Docker is
all you need — no Maven, Node, or JDK installed:

```bash
make docker-up     # builds and starts the container
```

Then open http://localhost:8080. `make docker-down` stops it. The image is
built and tagged locally (`load-balancer-sandbox:local`); nothing is pushed to
a registry.
````

- [ ] **Step 4: Commit**

```bash
git add Makefile README.md
git commit -m "docs(docker): make targets and README for docker run"
```