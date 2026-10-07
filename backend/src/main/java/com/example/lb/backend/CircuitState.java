package com.example.lb.backend;

/** State of the circuit breaker guarding a backend. */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}