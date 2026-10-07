package com.example.lb.backend;

import com.example.lb.backend.exceptions.BackendNotFoundException;
import com.example.lb.backend.exceptions.DuplicateBackendException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of the simulated backends, keyed by id.
 *
 * <p>Insertion order is tracked separately from the map so that {@link #all()}
 * and {@link #healthy()} return backends in a stable, predictable order.
 * Reads are lock-free; only the two mutating operations are synchronized so
 * that the map and the insertion order never diverge.
 */
public class BackendPool {

    private final ConcurrentHashMap<String, Backend> backends = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> insertionOrder = new CopyOnWriteArrayList<>();

    /**
     * Adds a backend under its own id.
     *
     * @throws DuplicateBackendException when a backend with that id is already registered
     */
    public synchronized void add(Backend backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        String id = backend.id();
        if (backends.putIfAbsent(id, backend) != null) {
            throw new DuplicateBackendException("backend already registered: " + id);
        }
        insertionOrder.add(id);
    }

    /**
     * Removes the backend with the given id and returns it.
     *
     * @throws BackendNotFoundException when no backend is registered under that id
     */
    public synchronized Backend remove(String id) {
        Objects.requireNonNull(id, "id must not be null");
        Backend removed = backends.remove(id);
        if (removed == null) {
            throw new BackendNotFoundException("no backend registered with id: " + id);
        }
        insertionOrder.remove(id);
        return removed;
    }

    public Optional<Backend> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(backends.get(id));
    }

    /** Returns every backend, in insertion order. */
    public List<Backend> all() {
        return insertionOrder.stream()
                .map(backends::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Returns the backends whose status is {@link BackendStatus#UP}, in insertion order. */
    public List<Backend> healthy() {
        return all().stream()
                .filter(backend -> backend.status() == BackendStatus.UP)
                .toList();
    }
}