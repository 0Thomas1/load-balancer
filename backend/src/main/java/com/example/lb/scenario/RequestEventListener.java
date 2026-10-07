package com.example.lb.scenario;

/**
 * Receives every request the {@link TrafficGenerator} emits.
 *
 * <p>Called on the generating thread after the balancer has answered, so a slow
 * listener slows the generator down; keep implementations cheap.
 */
@FunctionalInterface
public interface RequestEventListener {

    /** Called once per emitted request, after the balancer has routed it. */
    void onRequest(RequestEvent event);
}