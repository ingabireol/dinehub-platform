package com.dinehub.gateway.config;

import com.dinehub.gateway.filter.JwtAuthenticationGatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * How requests are counted for rate limiting.
 *
 * <p>Keyed by authenticated user where there is one, and by source address
 * otherwise. Keying everything by IP would put every customer behind one
 * corporate NAT into a single bucket, so one busy office could lock out a whole
 * organisation.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @Primary
    public KeyResolver userOrAddressKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders()
                    .getFirst(JwtAuthenticationGatewayFilter.HEADER_USER_ID);
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            var remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (remote != null ? remote.getAddress().getHostAddress() : "unknown"));
        };
    }

    /**
     * Separate, much tighter bucket for authentication endpoints.
     *
     * <p>Login and registration are where credential stuffing happens, and they
     * are expensive — BCrypt at cost 12 is deliberately slow, so an unthrottled
     * login endpoint is also a denial-of-service vector against our own CPU.
     */
    @Bean
    public KeyResolver authEndpointKeyResolver() {
        return exchange -> {
            var remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("auth:" + (remote != null
                    ? remote.getAddress().getHostAddress() : "unknown"));
        };
    }
}
