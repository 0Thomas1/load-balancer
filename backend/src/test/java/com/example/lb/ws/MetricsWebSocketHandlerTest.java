package com.example.lb.ws;

import com.example.lb.algorithm.AlgorithmStrategy;
import com.example.lb.algorithm.ConsistentHashStrategy;
import com.example.lb.algorithm.IPHashStrategy;
import com.example.lb.algorithm.LeastConnectionsStrategy;
import com.example.lb.algorithm.RandomStrategy;
import com.example.lb.algorithm.RoundRobinStrategy;
import com.example.lb.algorithm.WeightedRoundRobinStrategy;
import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.FailureReason;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.metrics.MetricsCollector;
import com.example.lb.scenario.ConsumerDropScenario;
import com.example.lb.scenario.RequestEvent;
import com.example.lb.scenario.ScenarioEngine;
import com.example.lb.scenario.TrafficGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the dashboard WebSocket handler against real collaborators: a real
 * collector, pool, balancer and scenario engine, with only the socket itself
 * stubbed.
 *
 * <p>Every test drives the handler through direct calls. The snapshot timer only
 * starts from {@code @PostConstruct}, which a directly constructed instance never
 * runs, so nothing here depends on timing or on {@code Thread.sleep}.
 */
class MetricsWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private MetricsCollector metrics;
    private BackendPool pool;
    private LoadBalancer loadBalancer;
    private ScenarioEngine scenarioEngine;
    private MetricsWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        metrics = new MetricsCollector(10_000L);
        pool = new BackendPool();
        pool.add(new Backend("b1", "Backend b1", new BackendConfig(10, 0.0, 100, 3)));
        pool.add(new Backend("b2", "Backend b2", BackendConfig.defaults()));
        loadBalancer = new LoadBalancer(
                pool, algorithms(), new CircuitBreaker(5, 60_000L), metrics);
        loadBalancer.setAlgorithm(RoundRobinStrategy.NAME);
        scenarioEngine = new ScenarioEngine(List.of(new ConsumerDropScenario(pool, new Random(7))));
        handler = new MetricsWebSocketHandler(
                objectMapper, metrics, pool, loadBalancer, scenarioEngine);
    }

    @Test
    void clientMessagesAreIgnored() throws Exception {
        StubSession session = connect();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"hello\"}"));

        assertThat(session.sent()).isEmpty();
    }

    @Test
    void broadcastSnapshotSendsATypedEnvelopeCarryingTheSnapshot() throws Exception {
        StubSession session = connect();
        metrics.record(true);
        metrics.record(true);
        metrics.record(false);

        handler.broadcastSnapshot(metrics.snapshot(pool, "round-robin", List.of("consumer-drop")));

        assertThat(session.sent()).hasSize(1);
        JsonNode message = objectMapper.readTree(session.sent().get(0).getPayload());
        assertThat(message.path("type").asText()).isEqualTo("snapshot");
        JsonNode payload = message.path("payload");
        assertThat(payload.isMissingNode()).isFalse();
        assertThat(payload.path("totalRequests").asLong()).isEqualTo(3);
        assertThat(payload.path("errorCount").asLong()).isEqualTo(1);
        assertThat(payload.path("activeAlgorithm").asText()).isEqualTo("round-robin");
        assertThat(payload.path("timestamp").asLong()).isPositive();
        assertThat(payload.path("backends")).hasSize(2);
        assertThat(payload.path("backends").get(0).path("id").asText()).isEqualTo("b1");
    }

    @Test
    void liveSnapshotCarriesTheActiveAlgorithmAndRunningScenarios() throws Exception {
        StubSession session = connect();
        scenarioEngine.start(ConsumerDropScenario.NAME, Map.of("count", "1"));
        loadBalancer.setAlgorithm(IPHashStrategy.NAME);

        handler.broadcastCurrentSnapshot();

        JsonNode payload = objectMapper.readTree(session.sent().get(0).getPayload()).path("payload");
        assertThat(payload.path("activeAlgorithm").asText()).isEqualTo("ip-hash");
        assertThat(payload.path("activeScenarios")).hasSize(1);
        assertThat(payload.path("activeScenarios").get(0).asText()).isEqualTo("consumer-drop");
    }

    @Test
    void onRequestSendsAnEventEnvelopeNamingTheBackendThatServed() throws Exception {
        StubSession session = connect();

        handler.onRequest(new RequestEvent("10.0.0.7", "b2", true, 4_000_000L, FailureReason.NONE));

        assertThat(session.sent()).hasSize(1);
        JsonNode message = objectMapper.readTree(session.sent().get(0).getPayload());
        assertThat(message.path("type").asText()).isEqualTo("event");
        JsonNode payload = message.path("payload");
        assertThat(payload.path("backendId").asText()).isEqualTo("b2");
        assertThat(payload.path("sourceIp").asText()).isEqualTo("10.0.0.7");
        assertThat(payload.path("success").asBoolean()).isTrue();
        assertThat(payload.path("latencyNanos").asLong()).isEqualTo(4_000_000L);
    }

    @Test
    void closedSessionsStopReceivingBroadcasts() throws Exception {
        StubSession session = connect();

        handler.broadcastCurrentSnapshot();
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.broadcastCurrentSnapshot();

        assertThat(session.sent()).hasSize(1);
    }

    @Test
    void aFailingSessionIsDroppedAndTheOthersKeepReceiving() throws Exception {
        StubSession broken = connect();
        StubSession healthy = connect();
        broken.failOnSend = true;

        handler.broadcastCurrentSnapshot();
        handler.broadcastCurrentSnapshot();

        assertThat(healthy.sent()).hasSize(2);
        assertThat(broken.sendAttempts).isEqualTo(1);
        assertThat(handler.sessionCount()).isEqualTo(1);
    }

    @Test
    void requestsServedByAGeneratorReachTheSubscribedSession() throws Exception {
        StubSession session = connect();
        try (TrafficGenerator generator = new TrafficGenerator(loadBalancer, null)) {
            generator.addListener(handler);

            generator.tick();
        }

        assertThat(session.sent()).hasSize(1);
        JsonNode message = objectMapper.readTree(session.sent().get(0).getPayload());
        assertThat(message.path("type").asText()).isEqualTo("event");
        assertThat(message.path("payload").path("backendId").asText()).isIn("b1", "b2");
    }

    private StubSession connect() throws Exception {
        StubSession session = new StubSession();
        handler.afterConnectionEstablished(session);
        return session;
    }

    private static Map<String, AlgorithmStrategy> algorithms() {
        List<AlgorithmStrategy> strategies = List.of(
                new RoundRobinStrategy(),
                new RandomStrategy(11),
                new LeastConnectionsStrategy(),
                new WeightedRoundRobinStrategy(),
                new IPHashStrategy(),
                new ConsistentHashStrategy());
        return strategies.stream()
                .collect(Collectors.toMap(AlgorithmStrategy::getName, Function.identity()));
    }

    /**
     * Minimal socket that records what the handler sent it; every other part of
     * the socket contract is irrelevant here and throws loudly if touched.
     */
    private static final class StubSession implements WebSocketSession {

        private final List<TextMessage> sent = Collections.synchronizedList(new ArrayList<>());
        private int sendAttempts;
        private boolean failOnSend;

        List<TextMessage> sent() {
            return List.copyOf(sent);
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            sendAttempts++;
            if (failOnSend) {
                // How Spring itself reports a socket that has already closed.
                throw new IllegalStateException("WebSocket session is closed");
            }
            sent.add((TextMessage) message);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public String getId() {
            return "stub-session";
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/metrics");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTextMessageSizeLimit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close(CloseStatus status) {
            throw new UnsupportedOperationException();
        }
    }
}