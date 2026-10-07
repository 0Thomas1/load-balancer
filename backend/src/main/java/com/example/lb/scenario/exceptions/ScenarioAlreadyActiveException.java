package com.example.lb.scenario.exceptions;

/** Thrown when starting a scenario that is already applied. */
public class ScenarioAlreadyActiveException extends RuntimeException {

    public ScenarioAlreadyActiveException(String message) {
        super(message);
    }
}