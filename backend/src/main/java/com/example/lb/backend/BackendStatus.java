package com.example.lb.backend;

/** Lifecycle status of a backend as seen by the load balancer. */
public enum BackendStatus {
    UP,
    DOWN,
    DRAINING
}