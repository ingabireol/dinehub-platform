package com.dinehub.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order service — the centre of the platform.
 *
 * <p>Owns the order lifecycle: {@code PLACED → PAID → PREPARING → READY →
 * DELIVERED}, or {@code CANCELLED} before preparation starts.
 *
 * <p>It publishes {@code order.placed} and reacts to {@code payment.completed},
 * {@code payment.failed} and {@code kitchen.status.updated}. The one synchronous
 * call it makes is to menu-service, to price a basket — an order has to be
 * priced at the moment it is placed and cannot wait for an event.
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.order", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.order", "com.dinehub.common"})
// Entity and repository scanning has to be stated explicitly. Spring Boot
// derives both from the package of the @SpringBootApplication class, not from
// scanBasePackages — so the shared processed_events entity and its repository
// in com.dinehub.common are invisible without these two lines. The failure is
// at startup, not at compile time, which is the cost of a shared module that
// contains Spring components.
@EntityScan(basePackages = {"com.dinehub.order.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.order.repository", "com.dinehub.common.messaging"})
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
