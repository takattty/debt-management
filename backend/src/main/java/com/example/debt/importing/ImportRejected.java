package com.example.debt.importing;

import java.util.List;
import java.util.UUID;

public final class ImportRejected extends RuntimeException {
    private final String code;
    private final UUID originalBatchId;
    private final List<CsvError> errors;

    ImportRejected(String code) { this(code, null, List.of()); }
    ImportRejected(String code, UUID originalBatchId, List<CsvError> errors) {
        super(code);
        this.code = code; this.originalBatchId = originalBatchId; this.errors = List.copyOf(errors);
    }
    public String code() { return code; }
    public UUID originalBatchId() { return originalBatchId; }
    public List<CsvError> errors() { return errors; }
}
