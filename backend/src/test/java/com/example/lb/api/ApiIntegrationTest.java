package com.example.lb.api;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.scenario.ScenarioEngine;
import com.example.lb.scenario.TrafficGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the REST surface against the real bean wiring.
 *
 * <p>The demo runner is present but does not start any scheduled work, so the
 * seeded pool is exactly the four backends the assertions below expect.
 *
 * <p>The tests share one application context, so they also share the mutable
 * beans: the pool, the active algorithm and the traffic rate. There is no
 * {@code @Order} on purpose — {@link #assertBaseline()} runs before every test
 * and fails loudly if anything leaked out of a previous one, and
 * {@link #restoreBaseline()} puts every one of those back. That makes each test
 * correct on its own, so the class passes in whatever order JUnit picks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.demo.autoStartTraffic=false")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BackendPool pool;

    @Autowired
    private LoadBalancer loadBalancer;

    @Autowired
    private TrafficGenerator generator;

    @Autowired
    private ScenarioEngine scenarioEngine;

    private static final List<String> SEEDED_IDS = List.of("b1", "b2", "b3", "b4");

    /**
     * The algorithm the balancer boots on: {@code BalancerConfig} picks
     * round-robin after the constructor's own deterministic default.
     */
    private static final String BASELINE_ALGORITHM = "round-robin";

    /** The generator's own default target rate, which it never leaves without being told to. */
    private static final long BASELINE_RATE = 20L;

    /**
     * Fails if a previous test left the shared beans somewhere else, which is
     * what makes the class safe to run unordered.
     */
    @BeforeEach
    void assertBaseline() {
        assertThat(loadBalancer.getAlgorithmName()).isEqualTo(BASELINE_ALGORITHM);
        assertThat(generator.targetRate()).isEqualTo(BASELINE_RATE);
        assertThat(generator.isBursting()).isFalse();
        assertThat(scenarioEngine.list()).allSatisfy(info -> assertThat(info.active()).isFalse());
        assertThat(pool.all()).hasSize(SEEDED_IDS.size());
    }

    @AfterEach
    void restoreBaseline() {
        scenarioEngine.stopAll();
        pool.all().stream()
                .map(Backend::id)
                .filter(id -> !SEEDED_IDS.contains(id))
                .toList()
                .forEach(pool::remove);
        for (String id : SEEDED_IDS) {
            pool.get(id).ifPresent(backend -> {
                backend.updateConfig(BackendConfig.defaults());
                backend.setStatus(BackendStatus.UP);
            });
        }
        loadBalancer.setAlgorithm(BASELINE_ALGORITHM);
        // A burst wins over the base rate, so it has to go first; setTargetRate
        // rejects 0, which is why the baseline is the generator's own default.
        generator.stopBurst();
        generator.setTargetRate(BASELINE_RATE);
    }

    @Test
    void listsTheSeededBackends() throws Exception {
        mockMvc.perform(get("/api/backends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", hasItems("b1", "b2", "b3", "b4")))
                .andExpect(jsonPath("$[0].name").value("Backend-1"))
                .andExpect(jsonPath("$[0].status").value("UP"));
    }

    @Test
    void createsBackendsAndRejectsDuplicatesAndInvalidConfigs() throws Exception {
        mockMvc.perform(post("/api/backends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "My Backend"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("my-backend"))
                .andExpect(jsonPath("$.name").value("My Backend"));

        // The id is derived from the name, so the same name collides.
        mockMvc.perform(post("/api/backends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "My Backend"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("my-backend")));

        mockMvc.perform(post("/api/backends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Broken", "latencyMs", -5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("latencyMs")));

        // A name that slugs to nothing would create a backend with an empty id.
        mockMvc.perform(post("/api/backends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "!!!"))))
                .andExpect(status().isBadRequest());

        assertThat(pool.get("broken")).isEmpty();
        assertThat(pool.get("-")).isEmpty();
    }

    @Test
    void deletesBackends() throws Exception {
        mockMvc.perform(post("/api/backends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Disposable"))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/backends/disposable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("disposable"));

        mockMvc.perform(delete("/api/backends/disposable"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/backends/never-existed"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesBackendConfig() throws Exception {
        mockMvc.perform(put("/api/backends/b1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latencyMs", 42, "failureRate", 0.25))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("b1"));

        Backend backend = pool.get("b1").orElseThrow();
        assertThat(backend.config().latencyMs()).isEqualTo(42);
        assertThat(backend.config().failureRate()).isEqualTo(0.25);
        // Untouched fields keep their defaults.
        assertThat(backend.config().capacity()).isEqualTo(BackendConfig.defaults().capacity());

        mockMvc.perform(put("/api/backends/b1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("failureRate", 2.0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("failureRate")));

        assertThat(pool.get("b1").orElseThrow().config().failureRate()).isEqualTo(0.25);

        mockMvc.perform(put("/api/backends/nope/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latencyMs", 10))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsTheSixAlgorithms() throws Exception {
        mockMvc.perform(get("/api/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*]", hasItems(
                        "consistent-hash", "ip-hash", "least-connections",
                        "random", "round-robin", "weighted-round-robin")));
    }

    @Test
    void switchesTheAlgorithm() throws Exception {
        mockMvc.perform(put("/api/algorithm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "least-connections"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("least-connections"));

        mockMvc.perform(put("/api/algorithm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "magic"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("magic")));
    }

    @Test
    void startsAndStopsScenarios() throws Exception {
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)));

        // No body at all is the same as no params: the scenario uses its defaults.
        mockMvc.perform(post("/api/scenarios/consumer-drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("consumer-drop"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(delete("/api/scenarios/consumer-drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/scenarios/consumer-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("consumer-drop"))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(pool.healthy()).hasSize(3);

        mockMvc.perform(post("/api/scenarios/consumer-drop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/scenarios/does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of())))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/scenarios/consumer-drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("consumer-drop"))
                .andExpect(jsonPath("$.active").value(false));

        assertThat(pool.healthy()).hasSize(4);

        mockMvc.perform(delete("/api/scenarios/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startingRecoveryWhenNothingIsDownIsReportedAsAConflict() throws Exception {
        // All four seeded backends are UP at baseline, so recovery has nothing
        // to do. The client must be able to show the reason (4xx + message),
        // not a bare 500 the user can't act on.
        mockMvc.perform(post("/api/scenarios/backend-recovery"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("no backend is DOWN")));
    }

    @Test
    void setsTheTrafficRate() throws Exception {
        mockMvc.perform(put("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rate", 50))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(50));

        mockMvc.perform(put("/api/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rate", -5))))
                .andExpect(status().isBadRequest());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}