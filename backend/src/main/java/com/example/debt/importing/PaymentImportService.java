package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentImportService {
    private final ImportStore store;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PaymentImportService(ImportStore store, JdbcClient jdbc, @Qualifier("importClock") Clock clock) {
        this.store = store; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional
    public ImportBatch preview(TenantUser user, InputStream source) {
        var preview = new PaymentCsv().parse(source, ids -> existingIds(user.tenantId(), ids));
        var now = clock.instant();
        var batch = new ImportBatch(UUID.randomUUID(), user.tenantId(), ImportBatch.Kind.PAYMENT, 1, preview.fingerprint(),
                preview.errors().isEmpty() ? ImportBatch.Status.PREVIEW_VALID : ImportBatch.Status.PREVIEW_INVALID,
                0, user.userId(), now, now.plus(Duration.ofHours(24)), null, null, preview.rows().size(), preview.errors().size());
        store.save(batch, preview.rows().stream().map(row -> new ImportStore.Snapshot(
                row.recordNumber(), row.rowNumber(), row, Map.of())).toList(), preview.errors());
        return store.get(user.tenantId(), batch.id(), false);
    }

    public Set<String> existingIds(UUID tenant, Set<String> ids) {
        return ids.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("SELECT transaction_id FROM payment WHERE tenant_id=:tenant AND transaction_id IN (:ids)")
                .param("tenant", tenant).param("ids", ids).query(String.class).list());
    }
}
