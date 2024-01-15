package com.dinehub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The single entry point for the platform.
 *
 * <p>Routes by path, validates JWTs once at the edge, and passes the verified
 * identity downstream as headers. Services still verify the token themselves —
 * that duplication is deliberate, so a request arriving by any other route
 * inside the cluster is not trusted simply for having arrived.
 *
 * <p>Deliberately not using Eureka. In Kubernetes a Service name already is
 * service discovery, with health checking and load balancing included; running
 * a discovery server alongside it adds a component to operate for no benefit.
 * See docs/adr/0001-kubernetes-services-over-eureka.md.
 */
@SpringBootApplication(scanBasePackages = "com.dinehub.gateway")
@ConfigurationPropertiesScan({"com.dinehub.gateway", "com.dinehub.common"})
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
