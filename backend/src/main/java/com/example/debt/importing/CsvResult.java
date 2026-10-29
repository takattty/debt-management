package com.example.debt.importing;

import java.util.List;

public record CsvResult(List<CsvRow> rows, List<CsvError> errors) {
    public CsvResult {
        rows = List.copyOf(rows);
        errors = List.copyOf(errors);
    }
}
