package com.example.lb.api;

import com.example.lb.api.dto.ScenarioStartRequest;
import com.example.lb.scenario.ScenarioEngine;
import com.example.lb.scenario.ScenarioInfo;
import com.example.lb.scenario.exceptions.ScenarioNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Starts and stops the failure scenarios. */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioEngine scenarioEngine;

    public ScenarioController(ScenarioEngine scenarioEngine) {
        this.scenarioEngine = scenarioEngine;
    }

    @GetMapping
    public List<ScenarioInfo> list() {
        return scenarioEngine.list();
    }

    /** Applies a scenario; an unknown name is a 404, an already active one a 409. */
    @PostMapping("/{name}")
    public ScenarioInfo start(@PathVariable String name, @RequestBody(required = false) ScenarioStartRequest request) {
        scenarioEngine.start(name, request == null ? null : request.params());
        return infoOf(name);
    }

    @DeleteMapping("/{name}")
    public ScenarioInfo stop(@PathVariable String name) {
        scenarioEngine.stop(name);
        return infoOf(name);
    }

    /** Reads the scenario's state back from the engine, which is the only source of truth. */
    private ScenarioInfo infoOf(String name) {
        return scenarioEngine.list().stream()
                .filter(info -> info.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ScenarioNotFoundException("no scenario registered with name: " + name));
    }
}