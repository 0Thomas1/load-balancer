package com.example.lb.api;

import com.example.lb.api.dto.BackendConfigUpdateRequest;
import com.example.lb.api.dto.BackendCreateRequest;
import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.exceptions.BackendNotFoundException;
import com.example.lb.metrics.BackendView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD over the backend pool. */
@RestController
@RequestMapping("/api/backends")
public class BackendController {

    private final BackendPool pool;

    public BackendController(BackendPool pool) {
        this.pool = pool;
    }

    @GetMapping
    public List<BackendView> list() {
        return pool.all().stream().map(BackendView::from).toList();
    }

    /** Registers a new backend, keyed by an id derived from its name. */
    @PostMapping
    public BackendView create(@RequestBody BackendCreateRequest request) {
        String name = require(request.name(), "name must not be blank");
        // Defaults first, then the fields the caller supplied: the
        // BackendConfig constructor is what rejects out-of-range values.
        BackendConfig config = BackendConfig.defaults().copyWith(
                request.latencyMs(), request.failureRate(), request.capacity(), request.weight());
        Backend backend = new Backend(idFor(name), name, config);
        pool.add(backend);
        return BackendView.from(backend);
    }

    @DeleteMapping("/{id}")
    public BackendView remove(@PathVariable String id) {
        return BackendView.from(pool.remove(id));
    }

    /** Applies a partial configuration update; omitted fields keep their value. */
    @PutMapping("/{id}/config")
    public BackendView updateConfig(@PathVariable String id, @RequestBody BackendConfigUpdateRequest request) {
        Backend backend = require(id);
        BackendConfig updated = backend.config().copyWith(
                request.latencyMs(), request.failureRate(), request.capacity(), request.weight());
        backend.updateConfig(updated);
        return BackendView.from(backend);
    }

    private Backend require(String id) {
        return pool.get(id).orElseThrow(() -> new BackendNotFoundException("no backend registered with id: " + id));
    }

    private static String require(String name, String message) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return name.trim();
    }

    /**
     * "My Backend" becomes {@code my-backend}: readable and stable across calls.
     *
     * <p>Non-alphanumerics collapse to a single dash, so a name made only of
     * punctuation would give a junk id like {@code "-"} rather than an empty
     * one; that is rejected here as a bad request.
     *
     * @throws IllegalArgumentException when the name has no letter or digit in it
     */
    private static String idFor(String name) {
        String id = name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        if (id.replace("-", "").isEmpty()) {
            throw new IllegalArgumentException("name must contain at least one letter or digit: " + name);
        }
        return id;
    }
}