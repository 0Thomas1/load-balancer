package com.example.lb.backend.exceptions;

/** Thrown when a backend id is looked up or removed but is not in the pool. */
public class BackendNotFoundException extends RuntimeException {

    public BackendNotFoundException(String message) {
        super(message);
    }
}