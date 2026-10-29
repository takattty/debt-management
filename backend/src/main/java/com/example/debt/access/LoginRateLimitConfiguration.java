package com.example.debt.access;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoginRateLimitConfiguration {
    @Bean("loginRateLimitClock")
    Clock loginRateLimitClock() { return Clock.systemUTC(); }
}
