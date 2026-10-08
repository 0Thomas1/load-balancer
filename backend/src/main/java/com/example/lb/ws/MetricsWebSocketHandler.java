package com.example.lb.ws;

import com.example.lb.backend.BackendPool;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.metrics.MetricsCollector;
import com.example.lb.metrics.MetricsSnapshot;
import com.example.lb.scenario.RequestEvent;
import com.example.lb.scenario.RequestEventListener;
import com.example.lb.scenario.ScenarioEngine;
import com.example.lb.scenario.ScenarioInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes the dashboard's two streams down every open socket: a full
 * {@link MetricsSnapshot} twice a second, and one message per simulated request
 * the moment it is served.
 *
 * <p>Both streams use the same envelope, {@code {"type": ..., "payload": ...}},
 * so a client can tell them apart on the type alone without inspecting the
 * payload's shape.
 *
 * <p>{@link #onRequest(RequestEvent)} runs on the {@code TrafficGenerator}'s
 * thread, and {@link RequestEventListener} documents that a slow listener slows
 * the generator down, so it does nothing beyond serializing and handing the
 * message to each socket. The snapshot timer runs on a daemon thread of its own
 * and owns its executor rather than sharing one, because a broadcast must not
 * queue behind scenario work.
 *
 * <p>Messages are sent to sessions from two threads at once, so the session list
 * is a {@link CopyOnWriteArrayList}: iteration is lock-free and a socket that
 * connects or drops mid-broadcast cannot disturb it. Tomcat's endpoint rejects a
 * send started while another is mid-frame, so writes to one socket are
 * serialized per socket. A socket that goes away is closed and dropped from the
 * list, so one dead client cannot silence the rest, and its browser gets a close
 * event it can reconnect to instead of waiting on a socket that serves nothing.
 */
@Component
public class MetricsWebSocketHandler extends TextWebSocketHandler implements RequestEventListener {

    /** How often a fresh snapshot is pushed to every socket. */
    static final long SNAPSHOT_INTERVAL_MS = 500L;

    private static final Logger log = LoggerFactory.getLogger(MetricsWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final MetricsCollector metrics;
    private final BackendPool pool;
    private final LoadBalancer loadBalancer;
    private final ScenarioEngine scenarioEngine;
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService timer;

    public MetricsWebSocketHandler(ObjectMapper objectMapper, MetricsCollector metrics, BackendPool pool,
                                   LoadBalancer loadBalancer, ScenarioEngine scenarioEngine) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer must not be null");
        this.scenarioEngine = Objects.requireNonNull(scenarioEngine, "scenarioEngine must not be null");
    }

    /**
     * Starts the snapshot timer. Separate from the constructor so a test can
     * build the handler and drive it by hand without a background thread
     * interleaving broadcasts.
     */
    @PostConstruct
    void startTimer() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metrics-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::broadcastCurrentSnapshot, SNAPSHOT_INTERVAL_MS, SNAPSHOT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        timer = scheduler;
    }

    /** Stops the snapshot timer; idempotent, and a no-op when it never started. */
    @PreDestroy
    void stopTimer() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    /** Number of sockets currently subscribed. */
    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }

    /**
     * Ignores anything a client sends. The socket exists to be written to, so a
     * message from the browser is not something this stream has to answer.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Intentionally empty: the dashboard never talks back.
    }

    /** Takes a snapshot right now and pushes it to every open socket. */
    public void broadcastSnapshot(MetricsSnapshot snapshot) {
        send("snapshot", snapshot);
    }

    /** Takes a snapshot of the live balancer state and pushes it to every open socket. */
    public void broadcastCurrentSnapshot() {
        broadcastSnapshot(metrics.snapshot(
                pool,
                loadBalancer.getAlgorithmName(),
                scenarioEngine.list().stream()
                        .filter(ScenarioInfo::active)
                        .map(ScenarioInfo::name)
                        .toList()));
    }

    /** Publishes one served request to every open socket. */
    @Override
    public void onRequest(RequestEvent event) {
        send("event", event);
    }

    /** Serializes one envelope and hands it to every socket, skipping the broken ones. */
    private void send(String type, Object payload) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
        } catch (JsonProcessingException e) {
            // A payload the mapper cannot read is a programming error, not a
            // reason to tear down the socket the dashboard is watching.
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                // Drop dead sockets instead of skipping them forever; a client
                // that vanished without a clean handshake would otherwise stay
                // in the list silently for the lifetime of the process.
                sessions.remove(session);
                continue;
            }
            try {
                // The generator thread and the snapshot thread both write to
                // every socket. Tomcat's endpoint state machine rejects a send
                // that starts while another is mid-frame (TEXT_PARTIAL_WRITING),
                // so writes to one socket are serialized per socket.
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException | RuntimeException e) {
                // The socket went away between the check and the send. Closing
                // it means the client's onclose fires and its reconnect logic
                // takes over, rather than the browser keeping a socket that
                // serves nothing until the page is refreshed by hand.
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                    // Already gone; removal below is all that is left to do.
                }
                log.warn("Dropping socket {} that failed to accept a broadcast: {}", session.getId(), e.toString());
                sessions.remove(session);
            }
        }
    }
}