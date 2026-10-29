package com.example.debt.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("closed")
class ClosedAccessTest {
    @Autowired MockMvc mvc;

    @Test
    void nonDevelopmentProfileDeniesApiEvenWithAuthenticatedUser() throws Exception {
        mvc.perform(get("/api/customers").with(user("unexpected").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/session").with(user("unexpected").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }
}
