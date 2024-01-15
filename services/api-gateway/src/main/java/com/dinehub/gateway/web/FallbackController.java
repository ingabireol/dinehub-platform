package com.dinehub.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.Map;

/**
 * What a client gets when a circuit breaker is open.
 *
 * <p>503 with a retriable message, in the same shape as every other error. The
 * alternative — letting the request hang until it times out — moves the outage
 * into the browser and makes the whole page feel broken rather than one feature.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service,
                                                        ServerWebExchange exchange) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", 503,
                        "error", "Service Unavailable",
                        "message", "The %s service is temporarily unavailable. Please try again shortly."
                                .formatted(service),
                        "path", exchange.getRequest().getURI().getPath(),
                        "traceId", traceId != null ? traceId : "none"));
    }
}
