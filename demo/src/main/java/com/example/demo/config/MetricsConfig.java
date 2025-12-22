package com.example.demo.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter clientRequestsCounter(MeterRegistry registry) {
        return Counter.builder("restaurant.client.requests.total")
                .description("Total number of client requests")
                .register(registry);
    }

    @Bean
    public Counter reservationRequestsCounter(MeterRegistry registry) {
        return Counter.builder("restaurant.reservation.requests.total")
                .description("Total number of reservation requests")
                .register(registry);
    }

    @Bean
    public Counter tableRequestsCounter(MeterRegistry registry) {
        return Counter.builder("restaurant.table.requests.total")
                .description("Total number of table requests")
                .register(registry);
    }

    @Bean
    public Timer clientOperationsTimer(MeterRegistry registry) {
        return Timer.builder("restaurant.client.operations.duration")
                .description("Duration of client operations")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer reservationOperationsTimer(MeterRegistry registry) {
        return Timer.builder("restaurant.reservation.operations.duration")
                .description("Duration of reservation operations")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer tableOperationsTimer(MeterRegistry registry) {
        return Timer.builder("restaurant.table.operations.duration")
                .description("Duration of table operations")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);
    }
}
