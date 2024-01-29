package com.dinehub.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * DineHub Payment Service.
 *
 * <p>Simulated payment processing. Owns the payments table.
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.payment", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.payment", "com.dinehub.common"})
// Entity and repository scanning is stated explicitly: Spring Boot derives both
// from the package of this class, not from scanBasePackages, so the shared
// processed_events entity and its repository would otherwise be invisible.
@EntityScan(basePackages = {"com.dinehub.payment.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.payment.repository", "com.dinehub.common.messaging"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
