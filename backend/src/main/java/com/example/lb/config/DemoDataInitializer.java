package com.example.lb.config;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.balancer.HealthChecker;
import com.example.lb.scenario.TrafficGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Puts a usable sandbox into the pool at startup, then lets it run.
 *
 * <p>Seeding always happens: the dashboard is useless against an empty pool.
 * Starting the generator and the health checker is what the tests turn off with
 * {@code app.demo.autoStartTraffic=false}, so that a test suite shares one
 * predictable pool instead of racing a background tick.
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    /** Ids of the seeded backends; the demo dashboard links to them by id. */
    private static final String[] SEEDED_IDS = {"b1", "b2", "b3", "b4"};

    private final BackendPool pool;
    private final TrafficGenerator generator;
    private final HealthChecker healthChecker;
    private final boolean autoStartTraffic;

    public DemoDataInitializer(BackendPool pool, TrafficGenerator generator, HealthChecker healthChecker,
                               @Value("${app.demo.autoStartTraffic:true}") boolean autoStartTraffic) {
        this.pool = pool;
        this.generator = generator;
        this.healthChecker = healthChecker;
        this.autoStartTraffic = autoStartTraffic;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String id : SEEDED_IDS) {
            pool.add(new Backend(id, "Backend-" + id.substring(1), BackendConfig.defaults()));
        }
        log.info("Seeded {} demo backends", SEEDED_IDS.length);
        if (autoStartTraffic) {
            generator.start();
            healthChecker.start();
            log.info("Traffic generator and health checker started");
        } else {
            log.info("app.demo.autoStartTraffic=false: leaving the sandbox idle");
        }
    }
}