package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerImportService {
    private final ImportStore store;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerImportService(ImportStore store, JdbcClient jdbc, @Qualifier("importClock") Clock clock) {
        this.store = store; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional
    public ImportBatch preview(TenantUser user, InputStream source) {
        CustomerCsv.Preview preview = new CustomerCsv().parse(source, codes -> existingCodes(user.tenantId(), codes));
        var now = clock.instant();
        ImportBatch batch = new ImportBatch(UUID.randomUUID(), user.tenantId(), ImportBatch.Kind.CUSTOMER, 1,
                preview.fingerprint(), preview.errors().isEmpty() ? ImportBatch.Status.PREVIEW_VALID : ImportBatch.Status.PREVIEW_INVALID,
                0, user.userId(), now, now.plus(Duration.ofHours(24)), null, null, preview.rows().size(), preview.errors().size());
        store.save(batch, preview);
        // Return the database's timestamp precision consistently with every later GET.
        return store.get(user.tenantId(), batch.id(), false);
    }

    Set<String> existingCodes(UUID tenant, Set<String> codes) {
        return codes.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("SELECT code FROM customer WHERE tenant_id=:tenant AND code IN (:codes)")
                .param("tenant", tenant).param("codes", codes).query(String.class).list());
    }
}
