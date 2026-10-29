package com.example.debt.invitation;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InvitationConfiguration {
    @Bean("invitationClock")
    Clock invitationClock() {
        return Clock.systemUTC();
    }
}
