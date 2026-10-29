package com.example.debt.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class MembershipFilter extends OncePerRequestFilter {
    private final UserAccounts accounts;
    private final AccessErrors errors;

    public MembershipFilter(UserAccounts accounts, AccessErrors errors) {
        this.accounts = accounts;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof TenantUser previous) {
            var account = accounts.byId(previous.userId());
            if (account.isEmpty() || !account.get().enabled()) {
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                errors.write(response, 401, "UNAUTHENTICATED", "もう一度ログインしてください。");
                return;
            }
            var user = account.get().principal();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        }
        chain.doFilter(request, response);
    }
}
