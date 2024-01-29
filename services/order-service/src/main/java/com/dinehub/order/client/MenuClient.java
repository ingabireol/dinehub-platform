package com.dinehub.order.client;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.order.dto.OrderDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks menu-service to price a basket.
 *
 * <p>The only synchronous service-to-service call in the platform. It exists
 * because an order must be priced at the moment it is placed — waiting for an
 * event would mean telling the customer a total some seconds after they
 * committed to it.
 *
 * <p>Because it is synchronous it is also the one place where menu-service being
 * down stops orders being placed. That is accepted and bounded: a short timeout,
 * and a clear 503 rather than a hang.
 */
@Component
public class MenuClient {

    private static final Logger log = LoggerFactory.getLogger(MenuClient.class);

    private final WebClient webClient;
    private final Duration timeout;

    public MenuClient(WebClient.Builder builder,
                      @Value("${dinehub.menu-service.url:http://localhost:8082}") String baseUrl,
                      @Value("${dinehub.menu-service.timeout:3s}") Duration timeout) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.timeout = timeout;
    }

    /**
     * @return the items that exist, which may be fewer than were asked for
     * @throws ApiExceptions.BadRequestException if menu-service cannot be reached
     */
    public List<OrderDtos.PricedItem> priceItems(List<UUID> itemIds) {
        try {
            List<OrderDtos.PricedItem> priced = webClient.post()
                    .uri("/api/v1/menu/items/pricing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("itemIds", itemIds))
                    .retrieve()
                    .bodyToFlux(OrderDtos.PricedItem.class)
                    // Short, and shorter than the client's patience. A pricing
                    // call that waits 30 seconds just moves the outage into the
                    // browser.
                    .timeout(timeout)
                    .collectList()
                    .block();

            return priced != null ? priced : List.of();

        } catch (WebClientResponseException e) {
            log.error("menu-service rejected the pricing request: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiExceptions.BadRequestException(
                    "Could not price this order. Please try again.");
        } catch (Exception e) {
            log.error("menu-service is unreachable", e);
            throw new ApiExceptions.BadRequestException(
                    "The menu is temporarily unavailable. Please try again shortly.");
        }
    }
}
