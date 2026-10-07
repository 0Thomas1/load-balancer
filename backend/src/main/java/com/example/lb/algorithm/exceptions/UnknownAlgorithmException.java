package com.example.lb.algorithm.exceptions;

/** Thrown when a requested algorithm name has no registered strategy. */
public class UnknownAlgorithmException extends RuntimeException {

    public UnknownAlgorithmException(String algorithmName) {
        super("Unknown algorithm: " + algorithmName);
    }
}