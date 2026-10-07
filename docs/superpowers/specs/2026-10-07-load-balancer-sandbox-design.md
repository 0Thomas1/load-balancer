# Load Balancer Sandbox — Design Spec

**Date:** 2026-10-07
**Status:** Approved for review
**Path:** Architectural (new project)

## Purpose

A learning/demo sandbox for understanding load balancing concepts. Users can watch
how a load balancer routes simulated traffic to simulated backend servers under
different algorithms, and trigger chaos scenarios (backend drops, traffic spikes,
latency injection, etc.) from a web UI to see how the system reacts in real time.

This is **not** a production-grade proxy. It is a self-contained visualization and
experimentation tool: backend servers are simulated in-process, traffic is
synthetic, and all state is in-memory.

**Success criteria:** a user can open the dashboard, switch algorithms, trigger
scenarios, and clearly see how routing distribution, backend health, error rates,
and circuit breaker states change in real time.

## Scope

### In scope
- Load balancer engine with 6 routing algorithms
- Simulated backend pool with configurable latency, failure rate, capacity
- Health checking and circuit breaking
- 8 chaos scenarios, startable/stoppable from the UI
- Synthetic traffic generator with adjustable rate
- Real-time React dashboard (charts, request-flow animation, stats)
- REST control API + WebSocket metrics stream
- Unit/integration tests for engine, scenarios, and API

### Out of scope
- Real HTTP proxying to external services
- Persistence/database
- Authentication
- Distributed/multi-instance operation

## Architecture

**Approach B: separate Spring Boot backend + React SPA.**

- Spring Boot 3.x (Java 17, Maven) exposes a REST API (`/api/*`) and a WebSocket
  (`/ws/metrics`).
- React 18 + Vite 5 frontend on its own dev server, proxying to the backend in
  development; in production the backend serves the built static assets.
- No database — all state lives in Spring singletons.
- WebSocket pushes a `MetricsSnapshot` every 500 ms; REST handles control
  operations (algorithm switch, scenario start/stop, backend config, traffic rate).

```
React SPA  ──REST (control)──▶  Spring Boot backend
React SPA  ◀──WebSocket (metrics)──  Spring Boot backend

Request flow:
Incoming Request → LoadBalancer.route()
  → HealthChecker.filter(backends)      // remove DOWN backends
  → AlgorithmStrategy.select(backends)  // pick one
  → CircuitBreaker.allowRequest(b)      // check circuit
  → Backend.simulate()                  // apply latency + failure rate
  → MetricsCollector.record()           // update stats
  → WebSocket broadcast                 // push to UI
```

## Components

### 1. Load Balancer Engine (`balancer/`, `algorithm/`)
- `LoadBalancer` — entry point; receives requests, delegates to the active strategy.
- `AlgorithmStrategy` — interface with implementations:
  - `RoundRobinStrategy`
  - `WeightedRoundRobinStrategy`
  - `LeastConnectionsStrategy`
  - `RandomStrategy`
  - `IPHashStrategy` (hashes source IP → backend; deterministic per IP)
  - `ConsistentHashStrategy` (hash ring over backends)
- `HealthChecker` — probes every 2 s; marks a backend DOWN after 3 consecutive
  failed probes, UP after 2 consecutive successful probes (defaults, configurable).
- `CircuitBreaker` — per-backend: opens after 5 consecutive failures, half-opens
  after a 10 s timeout, closes on first success in half-open. Intervals/thresholds
  are configurable.

### 2. Backend Pool (`backend/`)
- `Backend` — `id`, `name`, `weight`, `status` (UP / DOWN / DRAINING), `config`
  (`latency`, `failureRate`, `capacity`), `stats` (`activeConnections`,
  `totalRequests`, `errorCount`, `avgLatency`, `circuitState`).
- `BackendPool` — collection management (add / remove / update / notify).
- `Backend.simulate()` — applies configured latency and failure probability to a
  request.

### 3. Scenario Engine (`scenario/`)
- `TrafficGenerator` — generates synthetic requests at configurable requests/sec,
  with random source IPs (for IP Hash) and optional burst mode.
- `ChaosController` — applies/reverses scenario effects on the backend pool.
- `ScenarioEngine` — named scenario registry with start/stop lifecycle.
- `MetricsCollector` — aggregates stats, builds snapshots, broadcasts via WebSocket.

### 4. REST API (`api/`)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/backends` | List backends with live stats |
| POST | `/api/backends` | Add a backend |
| DELETE | `/api/backends/{id}` | Remove a backend |
| PUT | `/api/backends/{id}/config` | Update latency / failureRate / capacity / weight |
| GET | `/api/algorithms` | List available algorithms |
| PUT | `/api/algorithm` | Switch active algorithm |
| POST | `/api/scenarios/{name}` | Start a scenario (body: scenario params) |
| DELETE | `/api/scenarios/{name}` | Stop a scenario |
| GET | `/api/scenarios` | List scenarios + active status |
| PUT | `/api/traffic` | Set traffic rate / burst config |

### 5. WebSocket (`/ws/metrics`)

Pushes every 500 ms:

```json
{
  "timestamp": 1696789200000,
  "totalRequests": 1523,
  "requestsPerSecond": 47,
  "errorCount": 3,
  "activeAlgorithm": "ROUND_ROBIN",
  "activeScenarios": ["consumer-drop"],
  "backends": [
    {
      "id": "b1", "name": "Backend-1", "status": "UP", "weight": 1,
      "activeConnections": 5, "totalRequests": 512, "errorCount": 0,
      "avgLatency": 2.1, "circuitState": "CLOSED"
    }
  ]
}
```

## Chaos Scenarios

| Scenario | Effect | Reversible on stop |
|----------|--------|--------------------|
| Consumer Drop | Randomly marks N backends DOWN | Yes — restores status |
| Traffic Spike | Ramps to target RPS over 5 s, holds until stopped, ramps down over 5 s on stop | Yes — returns to base rate |
| Latency Injection | Adds artificial latency to target backends | Yes — restores config |
| Network Partition | Marks a subset DRAINING (finish in-flight, no new) | Yes — restores UP |
| Slow Backend | One backend: high latency + low capacity | Yes — restores config |
| Circuit Breaker Trip | One backend at 100% failure until circuit opens | Yes — resets circuit + config |
| Backend Recovery | Brings a DOWN backend back UP, clears circuit | Yes (idempotent) |
| Cascading Failure | Drops backends sequentially with delay | Yes — restores all |

Lifecycle: `POST /api/scenarios/{name}` → engine applies effect → metrics
broadcast updates UI → `DELETE /api/scenarios/{name}` reverses effect → broadcast.

Starting an already-active scenario returns `409 Conflict`.

## Web UI (React + Vite)

Layout: header with algorithm selector; main grid of panels.

| Component | Purpose |
|-----------|---------|
| TrafficChart | Line chart of requests/sec over time (Recharts) |
| BackendStatsGrid | Per-backend live table: status, requests, latency, errors, circuit state |
| RequestFlowViz | Animated SVG: client → LB → backends, colored by routing decisions |
| ScenarioPanel | Per-scenario dropdown + Start/Stop; "Stop All" button |
| AlgorithmSelector | Dropdown switching algorithms via REST |
| BackendConfigPanel | Add / edit / remove backends (latency, failureRate, capacity, weight) |

Tech: Tailwind CSS, native WebSocket via `useMetrics` hook (auto-reconnect with
exponential backoff), React context + hooks for state (no Redux). Vite dev proxy
→ backend.

## Error Handling

| Case | Behavior |
|------|----------|
| All backends DOWN / all circuits open | Return `503`; dashboard shows "no healthy backends" alert |
| Invalid config (negative latency, etc.) | `400` with message; mirrored client-side form validation |
| Unknown scenario or algorithm | `404` listing valid names |
| Duplicate scenario start | `409`; UI disables button while active |
| WebSocket disconnect | Client auto-reconnects with backoff; "reconnecting" badge |
| API errors in UI | Toast notifications |
| First load / partial data | Dashboard renders gracefully with empty state |

## Testing

**Backend (JUnit 5):**
- Algorithm unit tests: distribution correctness (Round Robin cycles; Weighted RR
  respects weights; Least Connections picks lowest; Random statistically uniform;
  IP Hash deterministic per IP; Consistent Hash stable under membership change).
- HealthChecker: DOWN after failed probes, UP after recovery.
- CircuitBreaker: open after N failures, half-open after timeout, close on success.
- ScenarioEngine: each scenario applies and reverses correctly.
- REST API: MockMvc happy path + validation errors for every endpoint.
- MetricsCollector: snapshot accuracy under concurrent recording.

**Frontend (Vitest + React Testing Library):**
- Dashboard renders metrics from mocked WebSocket.
- Scenario start/stop calls correct API and updates UI.
- Backend config form validation.

**Smoke:** boot the app, generate traffic, verify metrics flow end to end.

## Project Structure

```
load-balancer/
├── backend/                      # Spring Boot (Java 17, Maven)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/lb/
│       │   ├── LoadBalancerApplication.java
│       │   ├── algorithm/
│       │   ├── backend/
│       │   ├── balancer/
│       │   ├── scenario/
│       │   ├── metrics/
│       │   ├── api/
│       │   └── ws/
│       └── test/java/...         # mirrors main structure
├── frontend/                     # React 18 + Vite 5
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── components/
│       ├── hooks/
│       ├── App.jsx
│       └── main.jsx
├── docs/superpowers/specs/2026-10-07-load-balancer-sandbox-design.md
└── README.md
```

**Versions:** Java 17, Spring Boot 3.x, Maven, React 18, Vite 5, Tailwind,
Recharts, Vitest.
