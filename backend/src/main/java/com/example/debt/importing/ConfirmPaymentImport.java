package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ConfirmPaymentImport {
    private final PaymentConfirmationTransaction transaction;
    private final ImportStore store;

    public ConfirmPaymentImport(PaymentConfirmationTransaction transaction, ImportStore store) {
        this.transaction = transaction; this.store = store;
    }

    public ImportBatch confirm(TenantUser user, UUID id, long version) {
        try { return transaction.confirm(user, id, version); }
        catch (ImportRejected rejected) {
            // A matching batch can commit between the initial fingerprint read and the reference check.
            if (rejected.code().equals("IMPORT_REFERENCE_CONFLICT")) {
                UUID original = store.confirmedDuplicate(store.get(user.tenantId(), id, false));
                if (original != null) { rejected = transaction.duplicate(original); }
            }
            transaction.invalidate(user, id, version, rejected.errors()); throw rejected;
        }
        catch (DuplicateKeyException conflict) {
            ImportBatch batch = store.get(user.tenantId(), id, false);
            UUID original = store.confirmedDuplicate(batch);
            ImportRejected rejected = original != null ? transaction.duplicate(original)
                    : new ImportRejected("IMPORT_REFERENCE_CONFLICT", null, List.of(new CsvError(1, "file", "", false,
                            "PAYMENT_EXISTS", "同時操作で取引IDが登録されました。", "新しいプレビューで競合する行を確認してください。")));
            transaction.invalidate(user, id, version, rejected.errors()); throw rejected;
        }
    }
}
