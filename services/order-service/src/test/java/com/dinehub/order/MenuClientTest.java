package com.dinehub.order;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.order.client.MenuClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the one synchronous call in the platform, including the failure paths.
 *
 * <p>These matter because menu-service being slow or down is the single thing
 * that can stop orders being placed. Each case below is a real outage shape.
 */
class MenuClientTest {

    private MockWebServer menuService;
    private MenuClient client;

    @BeforeEach
    void setUp() throws IOException {
        menuService = new MockWebServer();
        menuService.start();
        client = new MenuClient(WebClient.builder(),
                menuService.url("/").toString(), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws IOException {
        menuService.shutdown();
    }

    @Test
    @DisplayName("returns the priced items menu-service sent")
    void returnsPricedItems() {
        UUID id = UUID.randomUUID();
        menuService.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [{"id":"%s","name":"Grilled Tilapia","price":12.50,
                          "available":true,"preparationMinutes":25}]
                        """.formatted(id)));

        var priced = client.priceItems(List.of(id));

        assertThat(priced).hasSize(1);
        assertThat(priced.getFirst().name()).isEqualTo("Grilled Tilapia");
        assertThat(priced.getFirst().price()).isEqualByComparingTo("12.50");
        assertThat(priced.getFirst().available()).isTrue();
    }

    @Test
    @DisplayName("an empty response is empty, not an error")
    void handlesEmptyResponse() {
        menuService.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        assertThat(client.priceItems(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("a 500 from menu-service becomes a clear client error, not a stack trace")
    void translatesServerError() {
        menuService.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.priceItems(List.of(UUID.randomUUID())))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("Could not price this order");
    }

    @Test
    @DisplayName("a slow menu-service times out rather than hanging the order")
    void timesOutRatherThanHanging() {
        // Without the timeout, a menu-service that accepts the connection and
        // then stops responding holds an order-service thread indefinitely while
        // the customer watches a spinner.
        menuService.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
                .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.priceItems(List.of(UUID.randomUUID())))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("temporarily unavailable");

        assertThat(System.currentTimeMillis() - start)
                .as("should give up at the configured timeout, not wait for the response")
                .isLessThan(2500);
    }

    @Test
    @DisplayName("an unreachable menu-service gives a retriable message")
    void handlesConnectionRefused() throws IOException {
        menuService.shutdown();

        assertThatThrownBy(() -> client.priceItems(List.of(UUID.randomUUID())))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("try again");
    }

    @Test
    @DisplayName("a sold-out item comes back with available=false rather than omitted")
    void reportsUnavailableItems() {
        // order-service needs to tell the customer which dish is sold out, so
        // menu-service returns it with a flag rather than leaving it out.
        UUID id = UUID.randomUUID();
        menuService.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [{"id":"%s","name":"Chocolate Tart","price":6.00,
                          "available":false,"preparationMinutes":5}]
                        """.formatted(id)));

        var priced = client.priceItems(List.of(id));

        assertThat(priced.getFirst().available()).isFalse();
        assertThat(priced.getFirst().name()).isEqualTo("Chocolate Tart");
    }
}
