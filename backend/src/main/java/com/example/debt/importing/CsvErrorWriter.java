package com.example.debt.importing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Writes spreadsheet-safe error reports; not the fixed-format re-import template. */
public final class CsvErrorWriter {
    public void write(List<CsvError> errors, OutputStream target) throws IOException {
        target.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        row(target, List.of("row_number", "field", "input_value", "input_truncated", "reason", "correction"));
        for (CsvError error : errors) {
            row(target, List.of(Integer.toString(error.rowNumber()), error.field(), error.inputValue(),
                    Boolean.toString(error.inputTruncated()), error.reason(), error.correction()));
        }
    }

    private void row(OutputStream target, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                target.write(',');
            }
            String safe = neutralize(values.get(index));
            target.write(('"' + safe.replace("\"", "\"\"") + '"').getBytes(StandardCharsets.UTF_8));
        }
        target.write(new byte[] {'\r', '\n'});
    }

    private String neutralize(String value) {
        int first = value.codePoints().dropWhile(point -> Character.isWhitespace(point) || Character.isSpaceChar(point)
                        || Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)
                .findFirst().orElse(-1);
        boolean control = value.codePoints().anyMatch(point -> point == '\t' || point == '\r' || point == '\n');
        return control || first != -1 && "=+-@".indexOf(first) >= 0 ? "'" + value : value;
    }
}
