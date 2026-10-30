package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceImportService {
    private final ImportStore store;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InvoiceImportService(ImportStore store, JdbcClient jdbc, @Qualifier("importClock") Clock clock) {
        this.store = store; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional
    public ImportBatch preview(TenantUser user, InputStream source) {
        InvoiceCsv.Preview preview = new InvoiceCsv().parse(source, codes -> customers(user.tenantId(), codes),
                numbers -> existingNumbers(user.tenantId(), numbers));
        var now = clock.instant();
        ImportBatch batch = new ImportBatch(UUID.randomUUID(), user.tenantId(), ImportBatch.Kind.INVOICE, 1,
                preview.fingerprint(), preview.errors().isEmpty() ? ImportBatch.Status.PREVIEW_VALID : ImportBatch.Status.PREVIEW_INVALID,
                0, user.userId(), now, now.plus(Duration.ofHours(24)), null, null, preview.rows().size(), preview.errors().size());
        var rows = preview.rows().stream().map(row -> {
            var reference = preview.customers().get(row.customerCode());
            return new ImportStore.Snapshot(row.recordNumber(), row.rowNumber(), row, reference == null ? Map.of()
                    : Map.of("customerId", reference.id(), "customerVersion", reference.version()));
        }).toList();
        store.save(batch, rows, preview.errors());
        return store.get(user.tenantId(), batch.id(), false);
    }

    public Map<String, InvoiceCsv.CustomerReference> customers(UUID tenant, Set<String> codes) {
        return codes.isEmpty() ? Map.of() : jdbc.sql("SELECT id,code,name,version FROM customer WHERE tenant_id=:tenant AND code IN (:codes)")
                .param("tenant", tenant).param("codes", codes).query((result, row) -> Map.entry(result.getString("code"),
                        new InvoiceCsv.CustomerReference(result.getObject("id", UUID.class), result.getLong("version"), result.getString("name"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Set<String> existingNumbers(UUID tenant, Set<String> numbers) {
        return numbers.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("SELECT invoice_number FROM invoice WHERE tenant_id=:tenant AND invoice_number IN (:numbers)")
                .param("tenant", tenant).param("numbers", numbers).query(String.class).list());
    }
}
