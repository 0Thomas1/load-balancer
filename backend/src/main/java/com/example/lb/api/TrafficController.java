package com.example.lb.api;

import com.example.lb.api.dto.TrafficRequest;
import com.example.lb.scenario.TrafficGenerator;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Drives the synthetic traffic rate. */
@RestController
public class TrafficController {

    private final TrafficGenerator generator;

    public TrafficController(TrafficGenerator generator) {
        this.generator = generator;
    }

    /**
     * Sets the base rate, or a burst when a positive duration comes with it.
     * A negative rate is rejected here so a bad request never reaches the
     * generator.
     */
    @PutMapping("/api/traffic")
    public Map<String, Long> setTraffic(@RequestBody TrafficRequest request) {
        if (request.rate() < 0) {
            throw new IllegalArgumentException("rate must be >= 0, but was " + request.rate());
        }
        Long burstDurationMs = request.burstDurationMs();
        if (burstDurationMs != null && burstDurationMs > 0) {
            generator.setBurst(request.rate(), burstDurationMs);
        } else {
            generator.setTargetRate(request.rate());
        }
        return Map.of("rate", generator.targetRate());
    }
}