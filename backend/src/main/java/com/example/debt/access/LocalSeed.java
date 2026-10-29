package com.example.debt.access;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("local")
public class LocalSeed {
    public static final UUID ALPHA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID BETA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID ALPHA_ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111101");
    public static final UUID BETA_ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222201");
    public static final UUID ALPHA_ACCOUNTANT = UUID.fromString("11111111-1111-1111-1111-111111111102");

    @Bean
    ApplicationRunner localUsers(JdbcClient jdbc, UserAccounts accounts, PasswordEncoder passwords,
            org.springframework.transaction.PlatformTransactionManager transactions,
            @Value("${LOCAL_SEED_PASSWORD}") String password) {
        PasswordPolicy.validateCreation(password);
        return args -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO tenant(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")
                    .params(ALPHA, "Fictional Alpha").update();
            jdbc.sql("INSERT INTO tenant(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")
                    .params(BETA, "Fictional Beta").update();
            seed(jdbc, accounts, passwords, password, ALPHA_ADMIN, ALPHA, "alpha-admin@example.test", UserRole.ADMIN);
            seed(jdbc, accounts, passwords, password, BETA_ADMIN, BETA, "beta-admin@example.test", UserRole.ADMIN);
            seed(jdbc, accounts, passwords, password, ALPHA_ACCOUNTANT, ALPHA, "alpha-accountant@example.test", UserRole.ACCOUNTANT);
        });
    }

    private void seed(JdbcClient jdbc, UserAccounts accounts, PasswordEncoder passwords, String password,
            UUID id, UUID tenant, String email, UserRole role) {
        var existing = accounts.byEmail(email);
        if (existing.isPresent()) {
            UserAccount user = existing.get();
            if (!user.id().equals(id) || !user.tenantId().equals(tenant) || user.role() != role) {
                throw new IllegalStateException("Local fixture identity collision");
            }
            return;
        }
        jdbc.sql("INSERT INTO app_user(id, tenant_id, email_canonical, password_hash, role) VALUES (?, ?, ?, ?, ?)")
                .params(id, tenant, email, passwords.encode(password), role.name()).update();
    }
}
