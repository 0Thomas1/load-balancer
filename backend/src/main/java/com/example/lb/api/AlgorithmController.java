package com.example.lb.api;

import com.example.lb.api.dto.AlgorithmSwitchRequest;
import com.example.lb.balancer.LoadBalancer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Inspects and switches the routing algorithm. */
@RestController
public class AlgorithmController {

    private final LoadBalancer loadBalancer;

    public AlgorithmController(LoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    @GetMapping("/api/algorithms")
    public List<String> list() {
        return loadBalancer.availableAlgorithms();
    }

    /** Activates an algorithm; an unknown name is a 404. */
    @PutMapping("/api/algorithm")
    public Map<String, String> switchAlgorithm(@RequestBody AlgorithmSwitchRequest request) {
        String name = request.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        loadBalancer.setAlgorithm(name);
        return Map.of("name", loadBalancer.getAlgorithmName());
    }
}