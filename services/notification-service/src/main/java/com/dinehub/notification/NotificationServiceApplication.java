package com.dinehub.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * DineHub Notification Service.
 *
 * <p>In-app notifications per user. Owns the notifications table.
 */
@SpringBootApplication(scanBasePackages = {"com.dinehub.notification", "com.dinehub.common"})
@ConfigurationPropertiesScan({"com.dinehub.notification", "com.dinehub.common"})
// Entity and repository scanning is stated explicitly: Spring Boot derives both
// from the package of this class, not from scanBasePackages, so the shared
// processed_events entity and its repository would otherwise be invisible.
@EntityScan(basePackages = {"com.dinehub.notification.entity", "com.dinehub.common.messaging"})
@EnableJpaRepositories(basePackages = {"com.dinehub.notification.repository", "com.dinehub.common.messaging"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
