package com.example.lb.config;

import com.example.lb.algorithm.AlgorithmStrategy;
import com.example.lb.backend.BackendPool;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.balancer.HealthChecker;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.metrics.MetricsCollector;
import com.example.lb.scenario.BackendRecoveryScenario;
import com.example.lb.scenario.CascadingFailureScenario;
import com.example.lb.scenario.CircuitBreakerTripScenario;
import com.example.lb.scenario.ConsumerDropScenario;
import com.example.lb.scenario.LatencyInjectionScenario;
import com.example.lb.scenario.NetworkPartitionScenario;
import com.example.lb.scenario.Scenario;
import com.example.lb.scenario.ScenarioEngine;
import com.example.lb.scenario.SlowBackendScenario;
import com.example.lb.scenario.TrafficGenerator;
import com.example.lb.scenario.TrafficSpikeScenario;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wires the simulation together as singletons.
 *
 * <p>The six strategies are {@code @Component}s whose bean names do not match
 * their {@link AlgorithmStrategy#getName()}, so they are folded by name into the
 * map the {@link LoadBalancer} expects; that map is what makes
 * {@code PUT /api/algorithm} able to address a strategy by its REST name.
 *
 * <p>Two schedulers exist on purpose. The generator owns the one it is given —
 * {@link TrafficGenerator#close()} shuts it down — so it gets a dedicated
 * thread. The cascade scenario only ever cancels its own tasks and never shuts a
 * scheduler down, so it shares the general-purpose one.
 */
@Configuration
public class BalancerConfig {

    /** Consecutive failures before a backend's circuit opens. */
    private static final int FAILURE_THRESHOLD = 5;

    /** How long an open circuit waits before it lets a probe through. */
    private static final long RESET_TIMEOUT_MS = 10_000L;

    @Bean
    public BackendPool backendPool() {
        return new BackendPool();
    }

    @Bean
    public CircuitBreaker circuitBreaker() {
        return new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS);
    }

    @Bean
    public MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    public LoadBalancer loadBalancer(BackendPool pool, CircuitBreaker breaker, MetricsCollector metrics,
                                    List<AlgorithmStrategy> strategies) {
        Map<String, AlgorithmStrategy> byName = strategies.stream()
                .collect(Collectors.toMap(AlgorithmStrategy::getName, Function.identity()));
        LoadBalancer balancer = new LoadBalancer(pool, byName, breaker, metrics);
        // The balancer activates the first name in sorted order, which would be
        // consistent-hash; round-robin is the one the demo is meant to show off.
        balancer.setAlgorithm("round-robin");
        return balancer;
    }

    /** The generator's own schedule; it is shut down with the generator. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService trafficScheduler() {
        return newScheduler("traffic-generator");
    }

    /** Shared by the scenarios that schedule work of their own. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService scheduler() {
        return newScheduler("scenario-scheduler");
    }

    @Bean
    public TrafficGenerator trafficGenerator(LoadBalancer loadBalancer,
                                             @Qualifier("trafficScheduler") ScheduledExecutorService scheduler) {
        return new TrafficGenerator(loadBalancer, scheduler);
    }

    @Bean
    public ConsumerDropScenario consumerDropScenario(BackendPool pool) {
        return new ConsumerDropScenario(pool, new Random());
    }

    @Bean
    public BackendRecoveryScenario backendRecoveryScenario(BackendPool pool, CircuitBreaker breaker) {
        return new BackendRecoveryScenario(pool, breaker);
    }

    @Bean
    public LatencyInjectionScenario latencyInjectionScenario(BackendPool pool) {
        return new LatencyInjectionScenario(pool);
    }

    @Bean
    public NetworkPartitionScenario networkPartitionScenario(BackendPool pool) {
        return new NetworkPartitionScenario(pool);
    }

    @Bean
    public SlowBackendScenario slowBackendScenario(BackendPool pool) {
        return new SlowBackendScenario(pool);
    }

    @Bean
    public CircuitBreakerTripScenario circuitBreakerTripScenario(BackendPool pool, CircuitBreaker breaker) {
        return new CircuitBreakerTripScenario(pool, breaker);
    }

    @Bean
    public CascadingFailureScenario cascadingFailureScenario(BackendPool pool,
                                                             @Qualifier("scheduler") ScheduledExecutorService scheduler) {
        return new CascadingFailureScenario(pool, scheduler);
    }

    @Bean
    public TrafficSpikeScenario trafficSpikeScenario(TrafficGenerator generator) {
        return new TrafficSpikeScenario(generator);
    }

    @Bean
    public ScenarioEngine scenarioEngine(List<Scenario> scenarios) {
        return new ScenarioEngine(scenarios);
    }

    @Bean(destroyMethod = "close")
    public HealthChecker healthChecker(BackendPool pool) {
        return new HealthChecker(pool, 2_000, 3, 500);
    }

    private static ScheduledExecutorService newScheduler(String threadName) {
        // Cancelled tasks are removed rather than left queued until their delay
        // elapses, which matters for the cascade's own tasks.
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}