package com.example.debt.access;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    @GetMapping("/api/session")
    Map<String, String> session(@AuthenticationPrincipal TenantUser user, CsrfToken csrf) {
        return Map.of("username", user.getUsername(), "csrfToken", csrf.getToken(),
                "csrfHeaderName", csrf.getHeaderName());
    }
}
