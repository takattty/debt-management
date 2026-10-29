package com.example.debt.importing;

import java.util.List;

public record CsvRow(int physicalStartLine, List<String> cells) {
    public CsvRow {
        cells = List.copyOf(cells);
    }
}
