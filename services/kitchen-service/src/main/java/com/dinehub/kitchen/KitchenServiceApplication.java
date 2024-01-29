package com.dinehub.kitchen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DineHub Kitchen Service.
 *
 * <p>The kitchen queue and ticket lifecycle. Owns kitchen tickets.
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.kitchen", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.kitchen", "com.dinehub.common"})
// Entity and repository scanning is stated explicitly: Spring Boot derives both
// from the package of this class, not from scanBasePackages, so the shared
// processed_events entity and its repository would otherwise be invisible.
@EntityScan(basePackages = {"com.dinehub.kitchen.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.kitchen.repository", "com.dinehub.common.messaging"})
@EnableScheduling
public class KitchenServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KitchenServiceApplication.class, args);
    }
}
