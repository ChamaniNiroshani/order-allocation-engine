package com.chamani.order_allocation_engine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled. The expiry job itself decides whether to do anything (checkout.expiry.enabled). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
