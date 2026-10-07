package com.example.lb.backend.exceptions;

/** Thrown when a backend id is added to the pool twice. */
public class DuplicateBackendException extends RuntimeException {

    public DuplicateBackendException(String message) {
        super(message);
    }
}