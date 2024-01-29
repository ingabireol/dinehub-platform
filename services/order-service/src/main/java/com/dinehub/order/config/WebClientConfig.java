package com.dinehub.order.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * The outbound HTTP client used to call menu-service.
 *
 * <p>Both timeouts are set explicitly. The defaults are effectively infinite,
 * which means a menu-service that accepts a connection and then stops responding
 * holds an order-service thread until something else gives up — and the customer
 * watches a spinner the whole time.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(5, TimeUnit.SECONDS)))
                .compress(true);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // Bounded. The default 256 KiB is plenty for a pricing response
                // and stops a misbehaving upstream exhausting memory here.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024));
    }
}
