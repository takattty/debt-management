package com.example.debt.access;

import com.example.debt.generated.api.AuthenticationApi;
import com.example.debt.generated.model.AuthLoginRequest;
import com.example.debt.generated.model.CsrfResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "auth-test"})
public class AuthenticationController implements AuthenticationApi {
    private final AuthenticateUser users;
    private final SessionAudit audit;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository contexts;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public AuthenticationController(AuthenticateUser users, SessionAudit audit, SessionAuthenticationStrategy sessions,
            SecurityContextRepository contexts, HttpServletRequest request, HttpServletResponse response) {
        this.users = users;
        this.audit = audit;
        this.sessions = sessions;
        this.contexts = contexts;
        this.request = request;
        this.response = response;
    }

    @Override
    public ResponseEntity<CsrfResponse> getCsrf() {
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(new CsrfResponse(csrf.getToken(), csrf.getHeaderName()));
    }

    @Override
    public ResponseEntity<Void> login(AuthLoginRequest credentials) {
        TenantUser user = users.authenticate(credentials.getEmail(), credentials.getPassword());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities());
        sessions.onAuthentication(authentication, request, response);
        try {
            audit.record(user, "SESSION_STARTED");
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException failure) {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            throw failure;
        }
    }
}
