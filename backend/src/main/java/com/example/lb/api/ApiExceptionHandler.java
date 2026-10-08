package com.example.lb.api;

import com.example.lb.algorithm.exceptions.UnknownAlgorithmException;
import com.example.lb.backend.exceptions.BackendNotFoundException;
import com.example.lb.backend.exceptions.DuplicateBackendException;
import com.example.lb.scenario.exceptions.ScenarioAlreadyActiveException;
import com.example.lb.scenario.exceptions.ScenarioNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the domain's exceptions into a status code and a one-message body, so
 * the dashboard never has to parse a stack trace.
 *
 * <p>Anything unanticipated is logged and reported as a bare 500: the detail
 * belongs in the server log, not in a response a client can read.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Error body of every failed request. */
    public record ApiError(String message) {
    }

    @ExceptionHandler({ScenarioNotFoundException.class, UnknownAlgorithmException.class, BackendNotFoundException.class})
    public ResponseEntity<ApiError> notFound(RuntimeException e) {
        return status(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({ScenarioAlreadyActiveException.class, DuplicateBackendException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException e) {
        return status(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return status(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception while serving an API request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal error"));
    }

    private static ResponseEntity<ApiError> status(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(new ApiError(e.getMessage()));
    }
}