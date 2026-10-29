package com.example.debt.access;

import com.example.debt.generated.api.SessionApi;
import com.example.debt.generated.model.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController implements SessionApi {
    private final CurrentUser currentUser;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public SessionController(CurrentUser currentUser, HttpServletRequest request, HttpServletResponse response) {
        this.currentUser = currentUser;
        this.request = request;
        this.response = response;
    }

    @Override
    public ResponseEntity<SessionResponse> getSession() {
        var user = currentUser.require();
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(new SessionResponse(user.getUsername(), csrf.getToken(), csrf.getHeaderName()));
    }

    @Override
    public ResponseEntity<Void> logout() {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
