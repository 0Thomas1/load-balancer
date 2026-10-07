package com.example.lb.scenario.exceptions;

/** Thrown when a scenario name is not in the {@link com.example.lb.scenario.ScenarioEngine} registry. */
public class ScenarioNotFoundException extends RuntimeException {

    public ScenarioNotFoundException(String message) {
        super(message);
    }
}