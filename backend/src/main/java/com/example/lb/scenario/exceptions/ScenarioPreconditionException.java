package com.example.lb.scenario.exceptions;

/**
 * Thrown when a scenario's preconditions do not hold for the sandbox's current
 * state, so the request conflicts with that state (for example starting
 * {@code backend-recovery} while no backend is DOWN).
 */
public class ScenarioPreconditionException extends RuntimeException {

    public ScenarioPreconditionException(String message) {
        super(message);
    }
}