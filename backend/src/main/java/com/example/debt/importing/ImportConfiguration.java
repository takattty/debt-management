package com.example.debt.importing;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImportConfiguration {
    @Bean("importClock")
    Clock importClock() {
        return Clock.systemUTC();
    }
}
