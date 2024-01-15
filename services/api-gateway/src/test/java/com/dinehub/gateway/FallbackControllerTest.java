package com.dinehub.gateway;

import com.dinehub.gateway.web.FallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    @DisplayName("an open circuit returns 503, not a hung request")
    void returnsServiceUnavailable() {
        // Letting the request hang until it times out moves the outage into the
        // browser and makes the whole page feel broken rather than one feature.
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders"));

        var response = controller.fallback("order", exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(503);
        assertThat(response.getBody().get("message").toString())
                .contains("order")
                .contains("try again");
    }

    @Test
    @DisplayName("the fallback uses the same error shape as every service")
    void matchesTheSharedErrorContract() {
        // One shape across the estate means the Angular client has one error
        // handler, not a special case for the gateway.
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments"));

        var body = controller.fallback("payment", exchange).getBody();

        assertThat(body).containsKeys("timestamp", "status", "error", "message", "path", "traceId");
    }

    @Test
    @DisplayName("an incoming trace id is carried into the fallback response")
    void preservesTraceId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header("X-Trace-Id", "trace-abc"));

        var body = controller.fallback("order", exchange).getBody();

        assertThat(body.get("traceId")).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("a request with no trace id still gets a trace field")
    void defaultsTraceId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));

        var body = controller.fallback("order", exchange).getBody();

        assertThat(body.get("traceId")).isEqualTo("none");
    }
}
