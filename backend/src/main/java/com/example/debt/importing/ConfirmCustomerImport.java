package com.example.debt.importing;

import com.example.debt.access.TenantUser;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ConfirmCustomerImport {
    private final ImportConfirmationTransaction transaction;
    private final ImportStore store;

    public ConfirmCustomerImport(ImportConfirmationTransaction transaction, ImportStore store) {
        this.transaction = transaction; this.store = store;
    }

    public ImportBatch confirm(TenantUser user, UUID id, long version) {
        try {
            return transaction.confirm(user, id, version);
        } catch (ImportRejected rejected) {
            if (rejected.code().equals("IMPORT_REFERENCE_CONFLICT")) {
                // Under READ COMMITTED an equivalent winner can commit after the transaction's duplicate read
                // but before its existing-code read. Resolve the original after rollback, just as for a unique-key race.
                ImportBatch batch = store.get(user.tenantId(), id, false);
                UUID original = store.confirmedDuplicate(batch);
                if (original != null) { rejected = transaction.duplicate(original); }
            }
            transaction.invalidate(user, id, version, rejected.errors());
            throw rejected;
        } catch (DuplicateKeyException conflict) {
            ImportBatch batch = store.get(user.tenantId(), id, false);
            UUID original = store.confirmedDuplicate(batch);
            ImportRejected rejected = original != null ? transaction.duplicate(original)
                    : new ImportRejected("IMPORT_REFERENCE_CONFLICT", null, List.of(new CsvError(1, "file", "", false,
                            "CUSTOMER_EXISTS", "同時操作で顧客コードが登録されました。", "新しいプレビューで競合する行を確認してください。")));
            transaction.invalidate(user, id, version, rejected.errors());
            throw rejected;
        }
    }
}
