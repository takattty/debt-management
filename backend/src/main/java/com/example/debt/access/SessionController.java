package com.example.debt.access;

import com.example.debt.generated.api.SessionApi;
import com.example.debt.generated.model.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "auth-test"})
public class SessionController implements SessionApi {
    private final CurrentUser currentUser;
    private final SessionAudit audit;
    private final SecurityContextRepository contexts;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public SessionController(CurrentUser currentUser, SessionAudit audit, SecurityContextRepository contexts,
            HttpServletRequest request, HttpServletResponse response) {
        this.currentUser = currentUser;
        this.audit = audit;
        this.contexts = contexts;
        this.request = request;
        this.response = response;
    }

    @Override
    public ResponseEntity<SessionResponse> getSession() {
        var user = currentUser.require();
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(new SessionResponse(user.userId(), user.email(),
                SessionResponse.RoleEnum.fromValue(user.role().name()), user.tenantId(), csrf.getToken(), csrf.getHeaderName()));
    }

    @Override
    public ResponseEntity<Void> logout() {
        var user = currentUser.require();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        try {
            audit.record(user, "SESSION_ENDED");
        } finally {
            var logout = new SecurityContextLogoutHandler();
            logout.setSecurityContextRepository(contexts);
            logout.logout(request, response, authentication);
            new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
        }
        return ResponseEntity.noContent().build();
    }
}
