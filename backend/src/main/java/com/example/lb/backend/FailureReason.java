package com.example.lb.backend;

/** Why a simulated request did not succeed. */
public enum FailureReason {
    NONE,
    BACKEND_FAILED,
    CAPACITY_EXCEEDED,
    NO_BACKENDS,
    CIRCUIT_OPEN
}