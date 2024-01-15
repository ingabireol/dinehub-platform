package com.dinehub.menu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Menu service.
 *
 * <p>Owns categories and items. Reads are public — a customer browses before
 * logging in — and writes require ADMIN.
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.menu", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.menu", "com.dinehub.common"})
// Entity and repository scanning has to be stated explicitly. Spring Boot
// derives both from the package of the @SpringBootApplication class, not from
// scanBasePackages — so the shared processed_events entity and its repository
// in com.dinehub.common are invisible without these two lines. The failure is
// at startup, not at compile time, which is the cost of a shared module that
// contains Spring components.
@EntityScan(basePackages = {"com.dinehub.menu.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.menu.repository", "com.dinehub.common.messaging"})
public class MenuServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MenuServiceApplication.class, args);
    }
}
