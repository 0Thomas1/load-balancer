# Load Balancer Sandbox

A self-contained, in-browser load balancer sandbox: a simulated Spring Boot
engine (no real proxying) paired with a real-time React dashboard. It exists to
make load-balancing concepts **visible** — algorithms, health checks, circuit
breakers, and chaos scenarios — not to route production traffic.

## What you get

- **6 balancing algorithms**: round-robin, weighted round-robin,
  least-connections, random, IP-hash, consistent-hash.
- **8 chaos scenarios** you can start and stop live.
- **A live dashboard**: per-backend metrics, throughput chart, request-flow
  animation, and WebSocket-streamed updates.
- Everything simulated in-memory in the backend — no database, no external
  services.

## Prerequisites

- JDK 17
- Node.js 18+ (tested with Node 24)
- No Maven install needed: the backend ships the Maven Wrapper (`./mvnw`).

## Quick start (dev)

Two terminals, both from the repository root:

```bash
# Terminal 1 — backend API + WebSocket on :8080
make dev-backend

# Terminal 2 — Vite dev server on :5173 (proxies /api and /ws to :8080)
make dev-frontend
```

Open http://localhost:5173 — the dashboard connects over WebSocket and starts
streaming snapshots and request events immediately.

## Building for production-style serving

The backend can serve the built frontend itself:

```bash
make build-frontend   # produces frontend/dist
make dev-backend      # backend now also serves the SPA on :8080
```

Then open http://localhost:8080. `WebConfig` activates only when
`frontend/dist` exists; otherwise the backend stays API-only for dev. If you launch
the packaged jar from a directory other than the repo root, point it at the build
output with `--app.frontend-dist=/absolute/path/to/frontend/dist`.

## Run with Docker

The whole sandbox (backend + dashboard) ships as a single image, so Docker is
all you need — no Maven, Node, or JDK installed:

```bash
make docker-up     # builds and starts the container
```

Then open http://localhost:8080. `make docker-down` stops it. The image is
built and tagged locally (`load-balancer-sandbox:local`); nothing is pushed to
a registry.

Note: the runtime base (`eclipse-temurin:17-jre-alpine`) is published for
`linux/amd64` only — JDK 17 Alpine has no arm64 build upstream — so the build
is pinned to that platform. On Apple Silicon the container runs under Rosetta
emulation; no action needed. If Adoptium later publishes arm64, the platform
pins in this plan and the Makefile can simply be dropped.

## Tests

```bash
make test-backend     # ./mvnw test (backend, 200+ tests)
make test-frontend    # vitest run (frontend)
```

## Commands

| Task             | Make target       |
|------------------|-------------------|
| Run backend      | `make dev-backend` |
| Run frontend     | `make dev-frontend` |
| Backend tests    | `make test-backend` |
| Frontend tests   | `make test-frontend` |
| Build frontend   | `make build-frontend` |

## REST API (subset)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/backends` | List backends |
| POST | `/api/backends` | Add a backend |
| GET | `/api/algorithms` | List algorithms |
| PUT | `/api/algorithm` | Set active algorithm (body `{"name":"..."}`) |
| GET | `/api/scenarios` | List scenarios |
| POST | `/api/scenarios/{name}` | Start a scenario |
| DELETE | `/api/scenarios/{name}` | Stop a scenario |
| PUT | `/api/traffic` | Set traffic rate (body `{"rate":N,...}`) |
| WS | `/ws/metrics` | Snapshot + event stream |

## Scenario catalog

| Scenario | What it does | What to watch |
|----------|--------------|---------------|
| `consumer-drop` | Simulated consumers vanish; backends are dropped to DOWN | Backend status flips, requests re-distribute |
| `traffic-spike` | Sudden burst of generated traffic | Throughput chart spikes |
| `latency-injection` | Artificial response latency added | Per-backend latency climbs |
| `network-partition` | Backends are isolated by draining them out of rotation | Status flips to DRAINING, traffic stops flowing there |
| `slow-backend` | One backend slows down | That backend's latency rises, load shifts away |
| `circuit-breaker-trip` | Forces a circuit breaker OPEN | Circuit badge turns red, traffic stops going there |
| `backend-recovery` | Brings a DOWN backend back UP | Backend returns, load re-balances |
| `cascading-failure` | One failure propagates to others | Multiple backends degrade together |

## Architecture

```
Browser (React + Vite + Tailwind + Recharts)
   │  WebSocket /ws/metrics          │  REST /api/*
   ▼                                 ▼
Spring Boot 3 backend (simulated, in-memory)
   ├─ LoadBalancer  ─► AlgorithmStrategy (6 implementations)
   ├─ BackendPool   ─► Backend (status, weight, connections, latency)
   ├─ CircuitBreaker (CLOSED / OPEN / HALF_OPEN)
   ├─ HealthChecker  (periodic probes, explicit recover())
   ├─ TrafficGenerator (rate/burst/ramp, emits RequestEvents)
   └─ ScenarioEngine  (8 chaos scenarios, start/stop)
```

- **Algorithms** pick a backend per request; the chosen backend's counters
  (requests, errors, latency) update live.
- **Scenarios** mutate pool/backend state at runtime; the dashboard reflects
  the change within one snapshot tick (500 ms).
- **WebSocket** pushes a full `snapshot` twice a second plus an `event` per
  request — the request-flow dots in the UI are driven by those events.

## Screenshots

*Placeholder — add a dashboard capture here after your first run.*

## Project layout

```
backend/   Spring Boot 3 (Java 17, Maven Wrapper) — engine + REST + WebSocket
frontend/  React 18 + Vite 5 — dashboard
docs/      design spec and the task-by-task implementation plan
```

## License / scope

This is a learning and demo tool. Backends are simulated in-process; nothing
is actually proxied. Algorithms and chaos scenarios are intentionally
simplified for readability.