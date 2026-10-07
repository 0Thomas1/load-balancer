# Load Balancer Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained load balancing sandbox — a Spring Boot engine with 6 algorithms, simulated backends, health checks, circuit breakers, 8 chaos scenarios, a synthetic traffic generator, and a React dashboard that visualizes it all in real time.

**Architecture:** Separate Spring Boot 3.x backend (REST control API + WebSocket metrics stream, all in-memory state) and a React 18 + Vite 5 SPA. Backend servers are simulated in-process with configurable latency, failure rate, and capacity. Traffic is synthetic; the UI drives the engine via REST and watches via WebSocket snapshots every 500 ms.

**Tech Stack:** Java 17, Spring Boot 3.2.x, Maven, Jackson, JUnit 5 (via spring-boot-starter-test), React 18, Vite 5, Tailwind CSS, Recharts, Vitest, React Testing Library. No extra backend dependencies beyond the standard starters (web + websocket).

**Spec:** `docs/superpowers/specs/2026-10-07-load-balancer-sandbox-design.md`

## Global Constraints

- Java 17, Spring Boot 3.2.x, Maven. Group `com.example`, artifact `load-balancer-backend`.
- Backend package base: `com.example.lb`. Sub-packages: `algorithm/`, `backend/`, `balancer/`, `scenario/`, `metrics/`, `api/`, `ws/`.
- Backend port `8080`; frontend dev server port `5173` (Vite default).
- REST prefix `/api`; WebSocket path `/ws/metrics`.
- No database. All state in Spring singletons, thread-safe: `ConcurrentHashMap`, `Atomic*` fields. Requests arrive from a scheduler thread and REST threads concurrently.
- WebSocket pushes a `MetricsSnapshot` every 500 ms. REST handles all control operations.
- Circuit breaker defaults (configurable): open after 5 consecutive failures, half-open after 10 s timeout, close on first success in half-open.
- Health checker defaults (configurable): probe every 2 s, mark DOWN after 3 consecutive failed probes. Probe fails when the backend is overloaded (`activeConnections >= capacity`) or `latencyMs >= probeTimeoutMs` (default 500 ms).
- Traffic spike ramps over exactly 5 s up and 5 s down (default 100 ms tick).
- Condition naming: `status` field enum `UP` / `DOWN` / `DRAINING`; `circuitState` enum `CLOSED` / `OPEN` / `HALF_OPEN`; algorithms named `round-robin`, `weighted-round-robin`, `least-connections`, `random`, `ip-hash`, `consistent-hash`; scenarios named `consumer-drop`, `traffic-spike`, `latency-injection`, `network-partition`, `slow-backend`, `circuit-breaker-trip`, `backend-recovery`, `cascading-failure`.
- JSON on the WebSocket is wrapped: `{"type":"snapshot","payload":{...}}` and `{"type":"event","payload":{...}}` (Jackson-held Records).
- Scenario params come through `POST /api/scenarios/{name}` as a JSON object of strings (`Map<String,String>`). Each scenario supplies defaults when a param is absent.
- Scenario lifecycle: `start(name,params)` → `scenario.apply()`; `stop(name)` → `scenario.revert()`; duplicate start throws `ScenarioAlreadyActiveException` (→ 409); unknown name throws `ScenarioNotFoundException` (→ 404).

## Review Focus

Input classes the spec implies but no core test exercises; each is tested in its owning task:

1. **All backends DOWN or all circuits OPEN** → `route()` must fail fast with `NO_BACKENDS` / `CIRCUIT_OPEN`, never NPE — the dashboard shows an alert. Test in Task 8.
2. **Weighted RR with zero weights** → no divide-by-zero; falls back to even distribution. Test in Task 3.
3. **IP Hash / Consistent Hash with blank IP** → deterministic fallback (hash of `""`), no NPE. Test in Task 4.
4. **Traffic rate 0 or negative** → `setTargetRate` rejects (→ 400 via API), generator keeps last valid rate, no runaway loop. Test in Task 9 and Task 13.
5. **Switching algorithm mid-traffic** → subsequent routes use the new strategy immediately; breaker/pool state untouched. Test in Task 8.

---

### Task 1: Project Scaffold + Backend Domain Model

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/example/lb/LoadBalancerApplication.java`
- Create: `backend/src/main/resources/application.properties` (server.port=8080)
- Create: `backend/src/main/java/com/example/lb/backend/BackendStatus.java`
- Create: `backend/src/main/java/com/example/lb/backend/CircuitState.java`
- Create: `backend/src/main/java/com/example/lb/backend/FailureReason.java`
- Create: `backend/src/main/java/com/example/lb/backend/SimulationResult.java`
- Create: `backend/src/main/java/com/example/lb/backend/BackendConfig.java`
- Create: `backend/src/main/java/com/example/lb/backend/BackendStats.java`
- Create: `backend/src/main/java/com/example/lb/backend/Backend.java`
- Test: `backend/src/test/java/com/example/lb/backend/BackendTest.java`
- Test: `backend/src/test/java/com/example/lb/backend/BackendConfigTest.java`

**Interfaces:**
- Produces: `BackendStatus{UP,DOWN,DRAINING}`; `CircuitState{CLOSED,OPEN,HALF_OPEN}`; `FailureReason{NONE,BACKEND_FAILED,CAPACITY_EXCEEDED,NO_BACKENDS,CIRCUIT_OPEN}`; `record SimulationResult(boolean success, long latencyNanos, FailureReason reason)`; `BackendConfig`; `BackendStats`; `Backend`.

- [ ] **Step 1: Scaffold the Maven project and run the smoke test**

Create `pom.xml` with parent `spring-boot-starter-parent` 3.2.5, Java 17, dependencies: `spring-boot-starter-web`, `spring-boot-starter-websocket`, `spring-boot-starter-test` (test). Create `LoadBalancerApplication` with a `main`. In `application.properties`, set `server.port=8080`.

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS (no tests yet, app context compiles).

Commit AFTER Step 2 wiring:

- [ ] **Step 2: Create the domain model**

Create `BackendStatus`, `CircuitState`, `FailureReason`, `SimulationResult` (records/enums exactly as above).

`BackendConfig` — immutable, validates in constructor:
- `latencyMs` >= 0, `0.0 <= failureRate <= 1.0`, `capacity` >= 1, `weight` >= 1 (all `IllegalArgumentException` otherwise).
- Fields + getters `latencyMs()`, `failureRate()`, `capacity()`, `weight()`, plus `copyWith(latencyMs, failureRate, capacity, weight)` null-coalescing overloads (each param `Long`/`Double`/`Integer`, null means "keep current").
- `public static BackendConfig defaults()` → `(latencyMs=5, failureRate=0.0, capacity=100, weight=1)`.

`BackendStats` — thread-safe counters via `AtomicInteger`/`AtomicLong`:
- `incrementActive()`, `decrementActive()`, `recordSuccess(long latencyNanos)`, `recordFailure(long latencyNanos)`, `setCircuitState(CircuitState)`.
- Getters `activeConnections()`, `totalRequests()`, `errorCount()`, `avgLatencyMs()` (totalLatencyNanos / totalRequests, 0 when none), `circuitState()`.

`Backend`:
- `public Backend(String id, String name, BackendConfig config)`; default `Random` (seeded `System.nanoTime()`).
- `public Backend(String id, String name, BackendConfig config, long randomSeed)` for deterministic tests.
- Fields: `AtomicReference<BackendConfig> config`, `AtomicReference<BackendStatus> status` (initial UP), `BackendStats stats`, `String id`, `String name`, `Random random`.
- `public SimulationResult simulate()`:
  1. If `stats.activeConnections() >= config.capacity()` → `SimulationResult(false, 0, CAPACITY_EXCEEDED)`.
  2. If `random.nextDouble() < config.failureRate()` → `SimulationResult(false, 0, BACKEND_FAILED)`.
  3. Else `Thread.sleep(config.latencyMs())` (millis; restore interrupt flag on `InterruptedException`), return `SimulationResult(true, latencyNanos = latencyMs*1_000_000, NONE)`.
- `updateConfig(BackendConfig)`, `setStatus(BackendStatus)`, getters `id()`, `name()`, `config()`, `status()`, `stats()`.

- [ ] **Step 3: Write the failing tests**

In `BackendConfigTest`: latency `-1`, failureRate `1.5`/`-0.1`, capacity `0`, weight `0` all throw `IllegalArgumentException`; `defaults()` values as above; `copyWith` updates only non-null fields.

In `BackendTest` (use `lastLatencyNanos` rounding tolerance: `assertThat(result.latencyNanos()).isGreaterThanOrEqualTo(TIME_UNIT_MS)`):
1. Seed backend, failureRate 0.0, latency 0 → `simulate()` succeeds; `result.reason() == NONE`.
2. failureRate 1.0 → `simulate()` fails with `BACKEND_FAILED`.
3. capacity 1, seed 42, call `simulate()` from one thread while another holds `stats.incrementActive()` → returns `CAPACITY_EXCEEDED`.
4. latency 10 → `latencyNanos() >= 10_000_000L`.
5. `stats.recordSuccess(...)`, `recordFailure(...)`, `incrementActive`/`decrementActive` reflect in `avgLatencyMs()`, `errorCount()`, `activeConnections()`.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS, all 5+ tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "feat(backend): scaffold project + backend domain model"
```

---

### Task 2: Backend Pool

**Files:**
- Create: `backend/src/main/java/com/example/lb/backend/BackendPool.java`
- Create: `backend/src/main/java/com/example/lb/backend/exceptions/BackendNotFoundException.java`
- Create: `backend/src/main/java/com/example/lb/backend/exceptions/DuplicateBackendException.java`
- Test: `backend/src/test/java/com/example/lb/backend/BackendPoolTest.java`

**Interfaces:**
- Consumes: `Backend` (Task 1).
- Produces: `BackendPool`, `BackendNotFoundException extends RuntimeException`, `DuplicateBackendException extends RuntimeException`.

- [ ] **Step 1: Write the failing test**

In `BackendPoolTest`:
1. `add(new Backend("b1", "B1", BackendConfig.defaults()))`, `add(b2)`, `all().size()==2`, order preserved.
2. `add` same id again → `DuplicateBackendException`.
3. `remove("b1")` → returns the backend; `get("b1")` empty.
4. `remove("missing")` → throws `BackendNotFoundException`.
5. `healthy()` returns only `status==UP`; `setStatus(DOWN)` then `healthy()` excludes it.
6. Concurrent adds from 4 threads (1000 each) → `all().size()==4000`, no exceptions.

- [ ] **Step 2: Implement `BackendPool`**

`ConcurrentHashMap<String,Backend>`. `add` throws `DuplicateBackendException` on existing id. `remove` throws `BackendNotFoundException` when missing. `get`. `all()` returns list in insertion order (track insertion order in a `CopyOnWriteArrayList` alongside the map, or sort by id — pick the list approach). `healthy()` filters `status()==UP`.

Create the two exception classes (plain `extends RuntimeException`).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS, all 6 scenarios.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(backend): thread-safe backend pool"
```

---

### Task 3: Algorithm Strategies I — Round Robin, Weighted Round Robin, Random, Least Connections

**Files:**
- Create: `backend/src/main/java/com/example/lb/algorithm/AlgorithmStrategy.java`
- Create: `backend/src/main/java/com/example/lb/algorithm/exceptions/UnknownAlgorithmException.java`
- Create: six impls under `algorithm/`
- Test: `backend/src/test/java/com/example/lb/algorithm/AlgorithmStrategyTest.java` (single file, 5 sections)

**Interfaces:**
- Consumes: `Backend`, `BackendPool` (Tasks 1–2).
- Produces: `interface AlgorithmStrategy { String getName(); Backend select(List<Backend> candidates, String sourceIp); }`; `UnknownAlgorithmException extends RuntimeException`.
- Naming by `getName()`: `round-robin`, `weighted-round-robin`, `random`, `least-connections`, `ip-hash`, `consistent-hash` (IP-Hash and Consistent Hash in Task 4).

- [ ] **Step 1: Write the failing tests**

Create `AlgorithmStrategyTest` (one file, distinct `@Nested` classes):

1. **RoundRobinStrategy**: `select` over `[b1,b2,b3]` called 6 times → returns b1,b2,b3,b1,b2,b3 in order (index `counter++ % size`).

2. **RandomStrategy**: over 10,000 iterations, each backend appears at least `9000/3` (statistical, seeded `long seed` passed to constructor `RandomStrategy(long seed)`).

3. **WeightedRoundRobinStrategy** (cycle-based, deterministically testable): backends with weights `[3:1:1]` → after 50 selections, counts are `30,10,10` exactly. Implementation: maintain a counter; compute `total = sum(weight)`; pick by `counter++ % total` walking cumulative weights. Note: use a `ConcurrentHashMap`-safe approach — guarded by `synchronized(this)`.

   Zero-weights: all backends `weight=0` → select falls back to a simple `counter % size` (no divide-by-zero); test asserts no exception and distribution is roughly even over 100 iterations.

4. **LeastConnectionsStrategy**: backends with `activeConnections()` 2,5,0 → selects the 0 one; tie-breaks by first in list.

- [ ] **Step 2: Implement the four strategies** (interface + `UnknownAlgorithmException` first)

Follow the pattern: `@Component` implementing `AlgorithmStrategy`, each with `getName()` returning its constant. Counter-based ones use `AtomicLong`.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS, all sections.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(algorithm): round-robin, weighted round-robin, random, least-connections"
```

---

### Task 4: Algorithm Strategies II — IP Hash, Consistent Hash

**Files:**
- Create: `backend/src/main/java/com/example/lb/algorithm/IPHashStrategy.java`
- Create: `backend/src/main/java/com/example/lb/algorithm/ConsistentHashStrategy.java`
- Test: `backend/src/test/java/com/example/lb/algorithm/HashStrategyTest.java`

**Interfaces:**
- Consumes: `AlgorithmStrategy`, `Backend` (Task 3 / Task 1).
- Produces: `IPHashStrategy`, `ConsistentHashStrategy`.

- [ ] **Step 1: Write the failing tests**

In `HashStrategyTest`:

1. **IPHashStrategy**: same IP always maps to same backend (50 calls, same result). Different IPs (e.g., "10.0.0.1", "10.0.0.2", "10.0.0.3") may differ. Blank/empty `sourceIp` → deterministic (hash of `""`), no exception.

2. **ConsistentHashStrategy**: with backends `[b1,b2,b3,b4]`, 100 random IPs → each backend receives a non-zero, roughly even share (within `20` of `100/4 + 4`). Remove `b3` from `candidates` (simulate server removed), re-select the same 100 IPs → at least 80% still map to the same backend as before. Blank `sourceIp` handled without exception.
   - Implementation: hash ring = for each backend, `100` virtual nodes at `hash(md5(id + "-" + i))` over the full integer range. Selection walks clockwise from `hash(ip)`. `Math.abs(md5 hash)` for consistency. Use `MessageDigest.getInstance("MD5")` (no extra deps).

- [ ] **Step 2: Implement both strategies**

Consistent hash ring rebuilt from `candidates` at each `select` call (correctness over micro-optimization).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(algorithm): ip-hash and consistent-hash strategies"
```

---

### Task 5: Circuit Breaker

**Files:**
- Create: `backend/src/main/java/com/example/lb/balancer/CircuitBreaker.java`
- Test: `backend/src/test/java/com/example/lb/balancer/CircuitBreakerTest.java`

**Interfaces:**
- Consumes: `CircuitState` (Task 1).
- Produces: `CircuitBreaker`.

- [ ] **Step 1: Write the failing tests**

`CircuitBreaker(int failureThreshold, long resetTimeoutMs)` — defaults `(5, 10_000)`; tests use small values like `(3, 50)`.
Methods: `boolean allowRequest(String backendId)`, `CircuitState state(String backendId)`, `boolean isOpen(String backendId)` (== state OPEN), `void onSuccess(String backendId)`, `void onFailure(String backendId)`, `void reset(String backendId)`.

1. **Opens after threshold**: `b1` fails `threshold` times → `state("b1") == OPEN`; `allowRequest("b1")` false.
2. **Half-open after timeout**: with `resetTimeoutMs=50`, after OPEN, advance past timeout (or `sleep(60)`), `state` becomes `HALF_OPEN`, `allowRequest("b1")` true exactly once (next call false while half-open probe in-flight).
3. **Closes on success in half-open**: after half-open, `onSuccess("b1")` → `state()==CLOSED`, `allowRequest` true.
4. **Re-opens on failure in half-open**: `onFailure` in half-open → `OPEN` again.
5. **Reset**: `reset("b1")` after OPEN → `CLOSED`.

- [ ] **Step 2: Implement `CircuitBreaker`**

Per-backend state: `Map<String, BackendCircuit>` where `BackendCircuit{volatile CircuitState state; int consecutiveFailures; long openedAt; volatile boolean probeInFlight}`. Use a `ConcurrentHashMap` + `computeIfAbsent`. Transitions:
- `onFailure`: increment consecutiveFailures; if `>= threshold` → state=OPEN, openedAt=now.
- `allowRequest`: if CLOSED → true; if OPEN and `now - openedAt >= resetTimeoutMs` → state=HALF_OPEN, probeInFlight=true, return true; if HALF_OPEN and probeInFlight → false. (HALF_OPEN allows exactly one probe request.)
- `onSuccess`: CLOSED → reset consecutiveFailures; HALF_OPEN → state=CLOSED, probeInFlight=false.
- `onFailure` in HALF_OPEN: state=OPEN, openedAt=now, probeInFlight=false.
- `reset(id)`: state=CLOSED, consecutiveFailures=0, probeInFlight=false.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(balancer): per-backend circuit breaker"
```

---

### Task 6: Health Checker

**Files:**
- Create: `backend/src/main/java/com/example/lb/balancer/HealthChecker.java`
- Test: `backend/src/test/java/com/example/lb/balancer/HealthCheckerTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `Backend` (Tasks 1–2).
- Produces: `HealthChecker`.

- [ ] **Step 1: Write the failing tests**

`HealthChecker(BackendPool pool, long probeIntervalMs, int failureThreshold, long probeTimeoutMs)` — defaults `(2_000, 3, 500)`; tests use tuned values.

1. **Overloaded → DOWN after threshold**: backend capacity 1, `stats.incrementActive()`, call `probeAll()` `failureThreshold` times → status `DOWN`.
2. **Slow → DOWN**: backend config latency 600 (> probeTimeout 500), `probeAll()` x3 → DOWN.
3. **Healthy stays UP**: default config backend, probe 20 times → stays UP.
4. **DOWN from overload stays DOWN even if health recovers** — after DOWN, `stats.decrementActive()`, probe more → still DOWN (recovery requires `recover(backend)`).
5. **`recover(backend)`** → status UP.
6. **DRAINING backends are not probed / not flipped** — a DRAINING backend stays DRAINING.

`probe(Backend)` returns `false` when `activeConnections >= capacity` or `latencyMs >= probeTimeoutMs`, else `true`. `probeAll()` iterates pool, updates status. Recovery is explicit via `recover(Backend)` (per scenario/action), NOT automatic (see Review Focus note — the spec's "UP after 2 consecutive successful probes" is intentionally not implemented so scenarios like Consumer Drop are stable; the BackendRecovery scenario is the recovery mechanism).

- [ ] **Step 2: Implement `HealthChecker`**

No scheduler needed for the core (so tests control timing); the wiring task (Task 14) schedules `probeAll()` every 2 s. Track `Map<String,Integer> consecutiveFailures` (`ConcurrentHashMap`). `probeAll()` loops `pool.all()`, ignores DRAINING, flips UP→DOWN at threshold.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(balancer): health checker with probe semantics"
```

---

### Task 7: Metrics Collector

**Files:**
- Create: `backend/src/main/java/com/example/lb/metrics/MetricsCollector.java`
- Create: `backend/src/main/java/com/example/lb/metrics/BackendView.java`
- Create: `backend/src/main/java/com/example/lb/metrics/MetricsSnapshot.java`
- Test: `backend/src/test/java/com/example/lb/metrics/MetricsCollectorTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `Backend`, `BackendStats`, `CircuitState`, `ScenarioInfo` (Tasks 1–2 and Task 11).
- Produces: `MetricsCollector`, `record BackendView(...)`, `record MetricsSnapshot(...)`.

- [ ] **Step 1: Write the failing tests**

`MetricsCollector(long windowMs)` (constructor-injectable window so tests use short windows; default 1000).

`record(boolean success)`:
- Increments the collector's `totalRequests`; if `!success`, increments `errorCount`; records an arrival timestamp in the rolling window.
- `requestsPerSecond()` prunes timestamps older than `windowMs` and returns the remaining count.
- `totalRequests()`, `errorCount()` return the counters. (Unroutable failures — `NO_BACKENDS`/`CIRCUIT_OPEN` — are counted here so they show up on the dashboard even though no backend received them. Per-backend counts come from each `Backend.stats()`.)

Tests:
1. `record(true)` x10 → `totalRequests()==10`, `errorCount()==0`, `requestsPerSecond()==10`.
2. `record(false)` x3 → `errorCount()==3`.
3. Window decay: with `windowMs=50`, `record(true)`, `Thread.sleep(60)`, `record(true)` → `requestsPerSecond()==1`.
4. `snapshot(pool, "round-robin", List.of("consumer-drop"))` returns correct `activeAlgorithm`, `activeScenarios`, and `backends` list built from the pool via `BackendView.from`.

- [ ] **Step 2: Implement**

`MetricsCollector` holds `AtomicLong totalRequests`, `AtomicLong errorCount`, `Deque<Long> window` (prune on access, `synchronized`). `MetricsSnapshot(long timestamp, long totalRequests, int requestsPerSecond, long errorCount, String activeAlgorithm, List<String> activeScenarios, List<BackendView> backends)` as a record. `BackendView.from(Backend)` maps id, name, status, weight, activeConnections, totalRequests, errorCount, avgLatency, circuitState.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(metrics): metrics collector, snapshot, backend view"
```

---

### Task 8: Load Balancer Engine

**Files:**
- Create: `backend/src/main/java/com/example/lb/balancer/LoadBalancer.java`
- Create: `backend/src/main/java/com/example/lb/balancer/RoutingResult.java`
- Test: `backend/src/test/java/com/example/lb/balancer/LoadBalancerTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `AlgorithmStrategy`, `CircuitBreaker`, `MetricsCollector` (Tasks 1, 2, 3/4, 5, 7).
- Produces: `LoadBalancer`, `record RoutingResult(String backendId, boolean success, long latencyNanos, FailureReason reason)`.

- [ ] **Step 1: Write the failing tests**

Constructor: `LoadBalancer(BackendPool pool, Map<String, AlgorithmStrategy> algorithms, CircuitBreaker breaker, MetricsCollector metrics)`.

1. **Round robin routing**: pool `[b1,b2]`, route 4 times → backendIds b1,b2,b1,b2.
2. **Skips DOWN and DRAINING**: `b2.setStatus(DOWN)` → always b1. `b2.setStatus(DRAINING)` → always b1.
3. **All DOWN → NO_BACKENDS**: both DOWN → `RoutingResult(null, false, 0, NO_BACKENDS)`; no exception.
4. **Circuit open skips backend**: `breaker.onFailure("b1")` x5 → routes all go to b2; both circuits open → `CIRCUIT_OPEN` reason.
5. **Records stats**: after routing, `b1.stats().totalRequests()` incremented; `metrics`/pool consistent.
6. **Switch algorithm take effect**: start round-robin, route once (b1), `setAlgorithm("least-connections")` with b1/b2 activeConnections 2/0 → next route → b2. `setAlgorithm("unknown")` → `UnknownAlgorithmException`. `availableAlgorithms()` returns the 6 names.
7. **Capacity overflow handled**: backend capacity 1, `incrementActive()` → next route returns reason `CAPACITY_EXCEEDED` (fail-fast, no sleep).

- [ ] **Step 2: Implement `LoadBalancer`**

`route(String sourceIp)`:
1. `candidates = pool.healthy()`.
2. Filter out `breaker.isOpen(id)`; if all filtered → return `CIRCUIT_OPEN` result.
3. If no candidates → `NO_BACKENDS`.
4. `selected = activeAlgorithm.select(filtered, sourceIp)`, `breaker.allowRequest` (track if half-open probe).
5. `selected.stats().incrementActive()`; `result = selected.simulate()`; `decrementActive()`.
6. `breaker.onSuccess/onFailure(id)`; `metrics.record(result.success())`.
7. Return `RoutingResult(id, result.success(), result.latencyNanos(), result.reason())` mapping `NONE`→`NONE`.

State: `AtomicReference<AlgorithmStrategy> activeAlgorithm`, `Map<String,AlgorithmStrategy>` registry, `getAlgorithmName()`, `setAlgorithm(String)`, `availableAlgorithms()` (sorted).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(balancer): load balancer engine"
```

---

### Task 9: Traffic Generator

**Files:**
- Create: `backend/src/main/java/com/example/lb/scenario/RequestEvent.java`
- Create: `backend/src/main/java/com/example/lb/scenario/RequestEventListener.java`
- Create: `backend/src/main/java/com/example/lb/scenario/TrafficGenerator.java`
- Test: `backend/src/test/java/com/example/lb/scenario/TrafficGeneratorTest.java`

**Interfaces:**
- Consumes: `LoadBalancer` (Task 8).
- Produces: `record RequestEvent(String sourceIp, String backendId, boolean success, long latencyNanos, FailureReason reason)`; `interface RequestEventListener { void onRequest(RequestEvent); }`; `TrafficGenerator`.

- [ ] **Step 1: Write the failing tests**

`TrafficGenerator(LoadBalancer loadBalancer, ScheduledExecutorService scheduler)` (or a `ScheduledExecutorService` passed in; a `Runnable tick()` method for direct control).

1. **Rate 0/negative rejected**: `setTargetRate(-1)` and `setTargetRate(0)` throw `IllegalArgumentException`.
2. **Emits configured rate**: `setTargetRate(10)`, run `tick()` 10 times (100 ms logic) → `loadBalancer.route` called ~10 times; verify via a counting `LoadBalancer` stub (static counter) or the pool's stats.
3. **Ramps rather than jumps** (5 s ramp): after `setTargetRate(100)`, first `tick()` currentRate is much less than 100 (e.g., `<= 30`); after many ticks (simulated over 5 s worth of ticks) approaches ~100.
4. **Burst mode**: `setBurst(200, 1000)` → `currentRate()` targets 200 while `isBursting()`; after 1 s (`stopBurst()` or duration expiry) returns to base rate.
5. **Request listers**: `addListener` receives `RequestEvent`s with `sourceIp` non-null and `backendId` matching a pool backend.

- [ ] **Step 2: Implement `TrafficGenerator`**

Scheduler runs every 100 ms; each tick emits `max(1, floor(currentRate/10))` requests, each: `ip = "10.0.0." + rand.nextInt(254)+1`, `result = loadBalancer.route(ip)`, then notify listeners. `currentRate` eases toward target: `currentRate += (target - currentRate) * (tickInterval/rampMs)` with rampMs=5000. Burst: `setBurst` sets a temporary high target with optional duration (0 duration = until `stopBurst()`); when burst expires, target back to base. Guard `setTargetRate` with a validation method. A `Runnable tick()` made `public` for test determinism. Each listener gets a CopyOnWriteArrayList.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(scenario): synthetic traffic generator"
```

---

### Task 10: Scenario Engine + Consumer Drop + Backend Recovery

**Files:**
- Create: `backend/src/main/java/com/example/lb/scenario/Scenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/ScenarioInfo.java`
- Create: `backend/src/main/java/com/example/lb/scenario/exceptions/ScenarioNotFoundException.java`
- Create: `backend/src/main/java/com/example/lb/scenario/exceptions/ScenarioAlreadyActiveException.java`
- Create: `backend/src/main/java/com/example/lb/scenario/ConsumerDropScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/BackendRecoveryScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/ScenarioEngine.java`
- Test: `backend/src/test/java/com/example/lb/scenario/ScenarioEngineTest.java`
- Test: `backend/src/test/java/com/example/lb/scenario/ConsumerDropScenarioTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `Backend`, `CircuitBreaker` (Tasks 1, 2, 5, 8).
- Produces: `Scenario`, `ScenarioInfo`, the two exception types, `ConsumerDropScenario`, `BackendRecoveryScenario`, `ScenarioEngine`.

- [ ] **Step 1: Write the failing tests**

`ScenarioEngine(List<Scenario> scenarios)` (registry by `name`) + `start(name, Map<String,String> params)`, `stop(name)`, `isActive(name)`, `list()` (List<ScenarioInfo>), `stopAll()`.

1. `list()` returns registry sorted by name with `active=false`.
2. Unknown name → `ScenarioNotFoundException`.
3. `start("consumer-drop", {"count":"2"})` → active; backends status DOWN.
4. Duplicate start → `ScenarioAlreadyActiveException`.
5. `stop` → backends restored UP; `active=false`.
6. `start` with missing params → defaults used (count=1).
7. Engine broadcasts nothing itself — just returns; caller reads state.

`ConsumerDropScenario(BackendPool pool, Random random)`:
1. Count > healthy pool size → clamps to healthy size.
2. `apply()` on `[b1,b2,b3]` count=1 → exactly one DOWN; others UP.
3. `revert()` → all UP; original config untouched.
4. Idempotent: calling revert when not active → no-op.

`BackendRecoveryScenario(BackendPool pool, CircuitBreaker breaker)` — recovers a specific or random DOWN backend: status UP + `breaker.reset(id)`. `apply()` throws `IllegalStateException` when no backend is DOWN. `revert()` is no-op.

- [ ] **Step 2: Implement**

`Scenario` interface: `String name()`, `String description()`, `void apply()`, `void revert()`.
`ScenarioEngine` holds `Map<String, Scenario> registry` and `Set<String> active` (ConcurrentHashMap). `start`: throw `ScenarioNotFoundException` if name missing; throw `ScenarioAlreadyActiveException` if already active; `scenario.apply()`, add to `active`. `stop`: remove from active + `revert()`. Implement the two scenarios with parameterized constructors (count via params).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(scenario): engine, consumer-drop, backend-recovery"
```

---

### Task 11: Config-Modifier Scenarios — Latency Injection, Network Partition, Slow Backend, Circuit Breaker Trip

**Files:**
- Create: `backend/src/main/java/com/example/lb/scenario/LatencyInjectionScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/NetworkPartitionScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/SlowBackendScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/CircuitBreakerTripScenario.java`
- Test: `backend/src/test/java/com/example/lb/scenario/ConfigScenariosTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `Backend`, `BackendConfig`, `CircuitBreaker` (Tasks 1, 2, 5, 8).
- Produces: the four scenario classes.

- [ ] **Step 1: Write the failing tests**

All scenarios: `apply()` followed by `stop()`/`revert()` restores original config/status exactly.

1. **LatencyInjectionScenario(pool, injectMs)** — default injectMs=100: `apply()` adds 100ms to every UP backend's latency; `revert()` restores.
2. **NetworkPartitionScenario(pool, count)** — default count=1: marks N UP backends DRAINING; `revert()` restores UP; skipped if already DRAINING.
3. **SlowBackendScenario(pool, backendId?)** — picks a backend (param `id`, else first UP): sets latency=500ms, capacity=1; `revert()` restores.
4. **CircuitBreakerTripScenario(pool, breaker, backendId?)** — sets failureRate=1.0 on a backend; run 5 routes → `breaker.state("bX")==OPEN`; `revert()` resets failureRate AND `breaker.reset(id)`.

- [ ] **Step 2: Implement**

Each captures original `BackendConfig`/`BackendStatus`/breaker state at `apply()`, restores at `revert()`. `SlowBackendScenario` and `CircuitBreakerTripScenario` accept `Map<String,String>` params for `backendId` (defaults: random UP backend).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(scenario): latency, partition, slow backend, circuit trip"
```

---

### Task 12: Async Scenarios — Cascading Failure, Traffic Spike

**Files:**
- Create: `backend/src/main/java/com/example/lb/scenario/CascadingFailureScenario.java`
- Create: `backend/src/main/java/com/example/lb/scenario/TrafficSpikeScenario.java`
- Test: `backend/src/test/java/com/example/lb/scenario/AsyncScenariosTest.java`

**Interfaces:**
- Consumes: `BackendPool`, `TrafficGenerator`, `ScheduledExecutorService` (Task 9).
- Produces: `CascadingFailureScenario`, `TrafficSpikeScenario`.

- [ ] **Step 1: Write the failing tests**

Use small delays (e.g., cascadeDelayMs=50) and a real scheduler.

1. **TrafficSpikeScenario(generator, targetRate)** — param `rate` default 200: `apply()` → `generator.setBurst(rate, 0)` (persistent until stopped); `revert()` → `generator.stopBurst()`, base rate restored. `isActive` true after apply.
2. **CascadingFailureScenario(pool, count=2, delay=50)** — `apply()` drops backends with a 50ms gap; after ~150ms, `count` backends are DOWN (first immediately, rest later). `revert()` (during or after) restores all to UP; pending future drops canceled.

- [ ] **Step 2: Implement**

`CascadingFailureScenario` schedules one `ConsumerDropScenario`-equivalent (drop one backend) every `delay`, up to count, on a shared scheduler; records dropped ids; `revert()` cancels pending tasks and restores all dropped backends to UP + original config. Implement via primitive scheduling logic; cleanup on task cancellation.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS. Watch for flakiness — use generous timeouts.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(scenario): cascading failure and traffic spike"
```

---

### Task 13: REST API + Bean Wiring + Demo Data

**Files:**
- Create: `backend/src/main/java/com/example/lb/api/dto/BackendCreateRequest.java`
- Create: `backend/src/main/java/com/example/lb/api/dto/BackendConfigUpdateRequest.java`
- Create: `backend/src/main/java/com/example/lb/api/dto/AlgorithmSwitchRequest.java`
- Create: `backend/src/main/java/com/example/lb/api/dto/ScenarioStartRequest.java`
- Create: `backend/src/main/java/com/example/lb/api/dto/TrafficRequest.java`
- Create: `backend/src/main/java/com/example/lb/api/BackendController.java`
- Create: `backend/src/main/java/com/example/lb/api/AlgorithmController.java`
- Create: `backend/src/main/java/com/example/lb/api/ScenarioController.java`
- Create: `backend/src/main/java/com/example/lb/api/TrafficController.java`
- Create: `backend/src/main/java/com/example/lb/api/ApiExceptionHandler.java`
- Create: `backend/src/main/java/com/example/lb/config/BalancerConfig.java`
- Create: `backend/src/main/java/com/example/lb/config/DemoDataInitializer.java`
- Test: `backend/src/test/java/com/example/lb/api/ApiIntegrationTest.java`
- Test: `backend/src/test/java/com/example/lb/config/DemoDataInitializerTest.java` (optional)

**Interfaces:**
- Consumes: everything from Tasks 1–12 + `Scenario`/`ScenarioEngine`/`TrafficGenerator`/`MetricsCollector`/`LoadBalancer`/`BackendPool`.
- Produces: the four controllers, DTOs, `ApiExceptionHandler`, `BalancerConfig`, `DemoDataInitializer`.

- [ ] **Step 1: Create `BalancerConfig` (bean wiring)**

A `@Configuration` defining beans:
- `BackendPool` (empty).
- `CircuitBreaker` defaults `(5, 10_000)`.
- `MetricsCollector`.
- `Map<String, AlgorithmStrategy>` with all six strategy beans (via `Map<String, AlgorithmStrategy>` injection or builder).
- `LoadBalancer` wired with above.
- `TrafficGenerator(loadBalancer, scheduler)` with `ScheduledExecutorService` bean (daemon threads, `setRemoveOnCancelPolicy(true)`).
- `ScenarioEngine` with all 8 scenario beans. Scenarios take params at `start(name, params)`; the simple param-less ones can be stateless beans.
- `HealthChecker` bean (probe every 2 s), started by `DemoDataInitializer`.

`DemoDataInitializer implements ApplicationRunner`:
- Seeds 4 backends `b1..b4` named "Backend-1..4", default `BackendConfig.defaults()`.
- Starts `TrafficGenerator` and `HealthChecker` scheduling **unless** `app.demo.autoStartTraffic=false` (property default `true`). Tests set the property to `false` for determinism.

- [ ] **Step 2: Implement controllers + DTOs + exception handler**

Exactly matching the spec's table:
- `BackendController`: `GET /api/backends` → `List<BackendView>`; `POST /api/backends` body `BackendCreateRequest(name, latencyMs, failureRate, capacity, weight)` → creates + adds, returns created `BackendView`; `DELETE /api/backends/{id}`; `PUT /api/backends/{id}/config` body `BackendConfigUpdateRequest` → validates via `BackendConfig` constructor + `copyWith`.
- `AlgorithmController`: `GET /api/algorithms` → list of names from `loadBalancer.availableAlgorithms()`; `PUT /api/algorithm` body `AlgorithmSwitchRequest(name)` → `loadBalancer.setAlgorithm(name)`; unknown → `UnknownAlgorithmException` (404).
- `ScenarioController`: `GET /api/scenarios` → `scenarioEngine.list()` (with active flags); `POST /api/scenarios/{name}` body `ScenarioStartRequest(params)` → `scenarioEngine.start(name, params)`, returns 200 with status; `DELETE /api/scenarios/{name}` → `stop`.
- `TrafficController`: `PUT /api/traffic` body `TrafficRequest(rate, burstDurationMs)` → validates `rate >= 0`, `generator.setTargetRate(rate)`; negative → 400.

`ApiExceptionHandler` (`@RestControllerAdvice`):
- `ScenarioNotFoundException`, `UnknownAlgorithmException`, `BackendNotFoundException` → 404, body `{"message": "..."}`.
- `ScenarioAlreadyActiveException` → 409.
- `DuplicateBackendException` → 409.
- `IllegalArgumentException` → 400.
- Fallback `Exception` → 500 (log).

- [ ] **Step 3: Write failing MockMvc tests in `ApiIntegrationTest`** (`@SpringBootTest` + `@AutoConfigureMockMvc`):

1. `GET /api/backends` returns 4 seeded backends (200).
2. `POST /api/backends` valid → 200 + new backend visible; duplicate id → 409; valid but invalid config (negative latency) → 400.
3. `DELETE /api/backends/{id}` → 200; `DELETE` missing → 404.
4. `PUT /api/backends/b1/config` valid → changes latency; invalid → 400.
5. `GET /api/algorithms` returns 6 names.
6. `PUT /api/algorithm` valid → 200; invalid name → 404.
7. `POST /api/scenarios/consumer-drop` → 200 + a backend DOWN; duplicate → 409; unknown → 404. `DELETE /api/scenarios/consumer-drop` restores.
8. `PUT /api/traffic` rate 50 → 200; rate -5 → 400.

Note: with `@SpringBootTest`, `DemoDataInitializer` runs and backends are seeded — good (GET /api/backends returns 4). Add `@TestPropertySource(properties = "app.demo.autoStartTraffic=false")` so the traffic generator and health checker do not run during tests, keeping assertions deterministic. If traffic IS helpful for a test (e.g., verifying backend request counters increase), start the generator via the bean reference.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "feat(api): REST controllers, DTOs, exception handling, demo wiring"
```

---

### Task 14: WebSocket Metrics Broadcast

**Files:**
- Create: `backend/src/main/java/com/example/lb/ws/WebSocketConfig.java`
- Create: `backend/src/main/java/com/example/lb/ws/MetricsWebSocketHandler.java`
- Modify: `backend/src/main/java/com/example/lb/config/BalancerConfig.java` (subscribe handler to traffic events)
- Test: `backend/src/test/java/com/example/lb/ws/MetricsWebSocketHandlerTest.java`

**Interfaces:**
- Consumes: `MetricsCollector`, `LoadBalancer`, `ScenarioEngine`, `TrafficGenerator`, `BackendPool`, `ObjectMapper` (Tasks 7, 8, 9, 10, 13).
- Produces: `WebSocketConfig`, `MetricsWebSocketHandler`.

- [ ] **Step 1: Write the failing tests**

`MetricsWebSocketHandler(ObjectMapper, MetricsCollector, BackendPool, LoadBalancer, ScenarioEngine)`:
1. `toStringBuffer`-style: on `handleTextMessage` client message, no-op; `broadcastSnapshot(MetricsSnapshot)` with a stubbed session (a test `WebSocketSession` that captures sent `TextMessage`s) → session receives a `TextMessage` whose JSON parses to `{"type":"snapshot","payload":{...}}`.
2. `onRequest(RequestEvent)` → broadcasts `{"type":"event","payload":{...}}` with the event's `backendId`.
3. `afterConnectionEstablished`/`afterConnectionClosed` add/remove sessions.

- [ ] **Step 2: Implement**

`WebSocketConfig implements WebSocketConfigurer` — registers handler at `/ws/metrics` with `.setAllowedOrigins("*")`. Handler: `CopyOnWriteArrayList<WebSocketSession> sessions`, a `ScheduledExecutorService` broadcasting `metrics.snapshot(...)` + `scenarioEngine.list()` (names of active) every 500 ms, and implements `RequestEventListener` (subscribe it to `trafficGenerator` during wiring — add to `BalancerConfig`: `trafficGenerator.addListener(handler)`).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/
git commit -m "feat(ws): metrics websocket with snapshot + event broadcast"
```

---

### Task 15: Frontend Scaffold

**Files:**
- Create: `frontend/` (full Vite React app)
- Create: `frontend/vite.config.js`, `frontend/index.html`, `frontend/tailwind.config.js`, `frontend/postcss.config.js`
- Create: `frontend/src/...` (empty shells)
- Test: `frontend/src/App.test.jsx` (smoke)

**Interfaces:**
- Produces: React + Vite app bootstrappable via `npm run dev` on port 5173; Tailwind wired.

- [ ] **Step 1: Scaffold**

`npm create vite@latest frontend -- --template react` (or manual `package.json`). Add `tailwindcss`, `postcss`, `autoprefixer`, `recharts`, `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`. Configure `vite.config.js`: plugin react, proxy `/api` and `/ws` to `http://localhost:8080` (`ws: true`). Tailwind config extends content globs to `./index.html, ./src/**/*.{js,jsx}`.

- [ ] **Step 2: Write failing smoke test + implement**

`App.test.jsx`: renders `App` → finds `Load Balancer Sandbox` header (render `App` with a mocked `useMetrics`? For scaffold, `App` renders a heading + empty panels). Use `vi.mock` for `useMetrics` returning empty snapshot.

- [ ] **Step 3: Run test to verify pass**

Run: `cd frontend && npm run test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): vite + react + tailwind scaffold"
```

---

### Task 16: Frontend Data Layer

**Files:**
- Create: `frontend/src/lib/ws.js`
- Create: `frontend/src/lib/api.js`
- Create: `frontend/src/hooks/useMetrics.js`
- Test: `frontend/src/hooks/useMetrics.test.jsx`

**Interfaces:**
- Produces: `connectWebSocket(url, {onSnapshot, onEvent, onStatus})`, `api.*` fetch wrappers, `useMetrics()` hook returning `{ snapshot, events, connectionState, error, reconnect }`.

- [ ] **Step 1: Write failing tests**

`useMetrics.test.jsx`:
1. On mount with a mocked `window.WebSocket` (`vi.stubGlobal`), `onSnapshot` invoked when server sends `{"type":"snapshot",...}`; `snapshot` state updated.
2. `onEvent` updates `events` array.
3. On socket close, `onStatus("reconnecting")` and a reconnect scheduled with exponential backoff (fake timers, attempt count progresses).
4. `connectionState` transitions to `"connected"`.

- [ ] **Step 2: Implement**

`connectWebSocket`: wraps `new WebSocket(url)`, dispatches `{type,payload}` messages, auto-reconnects after 500ms·2^attempts (cap 8s), reports state changes. `useMetrics`: `useState` for snapshot + bounded `events` deque (keep last 50), `useEffect` subscription. `api.js`: `getJSON`, `sendJSON` helpers + `getBackends`, `createBackend`, `updateBackendConfig`, `deleteBackend`, `getAlgorithms`, `setAlgorithm`, `getScenarios`, `startScenario`, `stopScenario`, `setTraffic`.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd frontend && npm run test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): websocket hook + api client"
```

---

### Task 17: Dashboard Components

**Files:**
- Create: `frontend/src/components/Dashboard.jsx`
- Create: `frontend/src/components/TrafficChart.jsx`
- Create: `frontend/src/components/BackendStatsGrid.jsx`
- Create: `frontend/src/components/RequestFlowViz.jsx`
- Create: `frontend/src/components/CircuitStateBadge.jsx`
- Test: `frontend/src/components/Dashboard.test.jsx`

**Interfaces:**
- Consumes: `useMetrics` (Task 16), `BackendView` shape from snapshot.
- Produces: dashboard layout with TrafficChart (Recharts `LineChart` of requestsPerSecond vs time), BackendStatsGrid (per-backend rows: status badge, requests, latency, errors, circuit badge), RequestFlowViz (animated SVG: client → LB → backend nodes; events from `events` array animate dots).

- [ ] **Step 1: Write failing test(s)**

`Dashboard.test.jsx`: with a mocked `useMetrics` returning a fixture snapshot (`backends: [b1,b2]`), assert backend names render; with a `connectionState:"reconnecting"`, a "reconnecting" badge shows; with empty backends, "no healthy backends" empty state renders when all DOWN.

- [ ] **Step 2: Implement components** — functional, Tailwind-styled; keep RequestFlowViz simple: render `Backend` nodes positioned in the target column; when a new event arrives, animate a dot from client to LB to that backend (CSS transition, keyed by an incrementing id, auto-remove after ~800 ms).

- [ ] **Step 3: Run tests to verify pass**

Run: `cd frontend && npm run test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): dashboard, traffic chart, backend grid, request flow"
```

---

### Task 18: Control Components

**Files:**
- Create: `frontend/src/components/ScenarioPanel.jsx`
- Create: `frontend/src/components/AlgorithmSelector.jsx`
- Create: `frontend/src/components/BackendConfigPanel.jsx`
- Create: `frontend/src/components/Toast.jsx`
- Test: `frontend/src/components/ControlComponents.test.jsx`

**Interfaces:**
- Consumes: `api.js` (Task 16), `useMetrics` snapshot (for active scenario flags when present).
- Produces: ScenarioPanel (per-scenario Start/Stop + "Stop All"), AlgorithmSelector (dropdown → `api.setAlgorithm`), BackendConfigPanel (add/edit/remove backends with validation), Toast (error surface).

- [ ] **Step 1: Write failing test(s)**

1. ScenarioPanel: with scenarios fixture `[{name:"consumer-drop",active:false}]`, clicking Start calls `api.startScenario` and shows "active" state; Stop calls `api.stopScenario`.
2. AlgorithmSelector: changing dropdown calls `api.setAlgorithm("least-connections")`.
3. BackendConfigPanel: submit with negative latency shows inline error, does NOT call `api.createBackend`; valid submit calls create.
4. Toast: an `api` rejection renders a toast message.

- [ ] **Step 2: Implement** — straightforward form + button components with minimal local state (no Redux). Toasts: small fixed-position stack driven by a `useErrorToasts` context or local state in `App`.

- [ ] **Step 3: Run tests to verify pass**

Run: `cd frontend && npm run test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): scenario panel, algorithm selector, backend config"
```

---

### Task 19: Production Wiring, README, and Smoke

**Files:**
- Modify: `backend/src/main/java/com/example/lb/config/WebConfig.java` (new; serves `frontend/dist` static + SPA fallback)
- Create: `README.md` (root)
- Create: `Makefile` (root — `dev-backend`, `dev-frontend`, `test-backend`, `test-frontend`, `build-frontend`)
- Modify: `.gitignore` (target/, node_modules/, dist/)

**Interfaces:**
- Consumes: done tasks.
- Produces: runnable demo + documentation.

- [ ] **Step 1: Add static-serving config**

In prod, `npm run build` produces `frontend/dist`; a `WebConfig` implementing `WebMvcConfigurer` maps `/` (and `/index.html`) to `file:../frontend/dist/` when the directory exists, with SPA fallback (`forward: /index.html` for non-file routes).

- [ ] **Step 2: Write root README + Makefile**

README: overview, prerequisites (JDK 17, Node 18+), quick start (2 terminals: `make dev-backend`, `make dev-frontend`), screenshots placeholder, architecture blurb, scenario catalog table.

- [ ] **Step 3: End-to-end smoke test (manual/harness)**

Boot backend; `curl http://localhost:8080/api/backends` expect 4 backends; `websocat`/browser connect to `ws://localhost:8080/ws/metrics` and observe snapshots; trigger `POST /api/scenarios/consumer-drop` and observe backend go DOWN; `DELETE` restores. If a WebSocket client isn't available, verify via logs/manual browser.

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "docs: README, Makefile, static serving, gitignore"
```

**Done.** After this task the sandbox is fully runnable.