package com.example.lb.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the dashboard's metrics socket at {@code /ws/metrics}.
 *
 * <p>Origins are open because the Vite dev server runs on its own origin and the
 * simulator has nothing to protect: it holds no real user data.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MetricsWebSocketHandler metricsHandler;

    public WebSocketConfig(MetricsWebSocketHandler metricsHandler) {
        this.metricsHandler = metricsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(metricsHandler, "/ws/metrics")
                .setAllowedOrigins("*");
    }
}