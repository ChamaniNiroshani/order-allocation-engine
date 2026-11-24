package com.chamani.order_allocation_engine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The application's notion of "now" (UTC). Injected instead of calling Instant.now(), so time-dependent rules can be tested with a fixed Clock. */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
