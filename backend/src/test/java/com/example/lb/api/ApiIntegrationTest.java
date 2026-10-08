package com.example.lb.api;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.scenario.ScenarioEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * seeded pool is exactly the four backends the assertions below expect. The
 * tests are ordered because they share that pool; {@link #resetDemoState()} puts
 * it back the way the runner seeded it after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.demo.autoStartTraffic=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BackendPool pool;

    @Autowired
    private ScenarioEngine scenarioEngine;

    private static final List<String> SEEDED_IDS = List.of("b1", "b2", "b3", "b4");

    @AfterEach
    void resetDemoState() {
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
    }

    @Test
    @Order(1)
    void listsTheSeededBackends() throws Exception {
        mockMvc.perform(get("/api/backends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].id", hasItems("b1", "b2", "b3", "b4")))
                .andExpect(jsonPath("$[0].name").value("Backend-1"))
                .andExpect(jsonPath("$[0].status").value("UP"));
    }

    @Test
    @Order(2)
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

        assertThat(pool.get("broken")).isEmpty();
    }

    @Test
    @Order(3)
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
    @Order(4)
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
    @Order(5)
    void listsTheSixAlgorithms() throws Exception {
        mockMvc.perform(get("/api/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*]", hasItems(
                        "consistent-hash", "ip-hash", "least-connections",
                        "random", "round-robin", "weighted-round-robin")));
    }

    @Test
    @Order(6)
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
    @Order(7)
    void startsAndStopsScenarios() throws Exception {
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)));

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
    @Order(8)
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