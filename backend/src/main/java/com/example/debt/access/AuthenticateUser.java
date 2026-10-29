package com.example.debt.access;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticateUser {
    private final UserAccounts accounts;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public AuthenticateUser(UserAccounts accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }

    public TenantUser authenticate(String email, String password) {
        PasswordPolicy.validateLogin(password);
        String canonical = new EmailAddress(email).canonical();
        var account = accounts.byEmail(canonical);
        boolean matches = passwords.matches(password, account.map(UserAccount::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !matches || !account.get().enabled()) {
            throw new LoginRejected();
        }
        return account.get().principal();
    }
}
