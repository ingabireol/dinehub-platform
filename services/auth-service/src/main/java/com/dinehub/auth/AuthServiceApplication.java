package com.dinehub.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Auth service.
 *
 * <p>Owns users and issues tokens. Every other service validates those tokens
 * offline using the shared signing key, so a login outage does not stop requests
 * that are already authenticated — the blast radius of this service being down
 * is "nobody new can log in", not "nothing works".
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.auth", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.auth", "com.dinehub.common"})
// Entity and repository scanning has to be stated explicitly. Spring Boot
// derives both from the package of the @SpringBootApplication class, not from
// scanBasePackages — so the shared processed_events entity and its repository
// in com.dinehub.common are invisible without these two lines. The failure is
// at startup, not at compile time, which is the cost of a shared module that
// contains Spring components.
@EntityScan(basePackages = {"com.dinehub.auth.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.auth.repository", "com.dinehub.common.messaging"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
