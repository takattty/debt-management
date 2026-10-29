package com.example.debt.importing;

import com.example.debt.customer.CustomerValues;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fixed customer format and B01: previews never update existing customer codes. */
public final class CustomerCsv {
    public static final String HEADER = "customer_code,customer_name\r\n";
    public record Row(int recordNumber, int rowNumber, String customerCode, String customerName, boolean valid) { }
    public record Preview(List<Row> rows, List<CsvError> errors, String fingerprint) {
        public Preview {
            rows = List.copyOf(rows);
            errors = List.copyOf(errors);
        }
    }

    public Preview parse(InputStream source, Function<Set<String>, Set<String>> findExisting) {
        CsvResult parsed = new CsvParser().parse(source);
        List<CsvError> errors = new ArrayList<>(parsed.errors());
        List<Row> rows = new ArrayList<>();
        if (errors.isEmpty() && (parsed.rows().isEmpty()
                || !parsed.rows().getFirst().cells().equals(List.of("customer_code", "customer_name")))) {
            errors.add(error(1, "header", parsed.rows().isEmpty() ? "" : String.join(",", parsed.rows().getFirst().cells()),
                    "INVALID_HEADER", "ヘッダーが固定形式と異なります。", "テンプレートの列名と順序を使用してください。"));
        } else if (errors.isEmpty()) {
            for (CsvRow row : parsed.rows().subList(1, parsed.rows().size())) {
                int before = errors.size();
                if (row.cells().size() != 2) {
                    errors.add(error(row.physicalStartLine(), "columns", String.join(",", row.cells()),
                            "COLUMN_COUNT", "列数が2列ではありません。", "customer_code,customer_nameの2列にしてください。"));
                }
                String code = field(row, 0, "customer_code", errors);
                String name = field(row, 1, "customer_name", errors);
                rows.add(new Row(rows.size() + 1, row.physicalStartLine(), code, name, errors.size() == before));
            }
            if (rows.isEmpty()) {
                errors.add(error(1, "file", "", "EMPTY_IMPORT", "データ行がありません。", "ヘッダーの次の行から顧客を入力してください。"));
            }
            Set<String> codes = rows.stream().filter(Row::valid).map(Row::customerCode).collect(Collectors.toSet());
            Set<String> existing = codes.isEmpty() ? Set.of() : findExisting.apply(codes);
            Map<String, Integer> counts = new HashMap<>();
            rows.stream().filter(Row::valid).forEach(row -> counts.merge(row.customerCode(), 1, Integer::sum));
            for (Row row : rows) {
                if (row.valid() && counts.get(row.customerCode()) > 1) {
                    errors.add(error(row.rowNumber(), "customer_code", row.customerCode(), "DUPLICATE_CODE",
                            "同じCSVに同一顧客コードが複数あります。", "顧客コードを重複させず1行だけにしてください。"));
                }
                if (row.valid() && existing.contains(row.customerCode())) {
                    errors.add(error(row.rowNumber(), "customer_code", row.customerCode(), "CUSTOMER_EXISTS",
                            "顧客コードは登録済みです。", "CSVは新規登録専用です。既存顧客は個別画面で変更してください。"));
                }
            }
        }
        Set<Integer> invalidLines = errors.stream().map(CsvError::rowNumber).collect(Collectors.toSet());
        rows = rows.stream().map(row -> new Row(row.recordNumber(), row.rowNumber(), row.customerCode(),
                row.customerName(), !invalidLines.contains(row.rowNumber()))).toList();
        rows = rows.stream().map(row -> new Row(row.recordNumber(), row.rowNumber(), visible(row.customerCode()),
                visible(row.customerName()), row.valid())).toList();
        errors = errors.stream().map(error -> new CsvError(error.rowNumber(), error.field(), visible(error.inputValue()),
                error.inputTruncated(), error.reasonCode(), error.reason(), error.correction())).toList();
        return new Preview(rows, errors, fingerprint(rows));
    }

    private String field(CsvRow row, int index, String field, List<CsvError> errors) {
        String raw = row.cells().size() > index ? row.cells().get(index) : "";
        try {
            if (raw.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Control characters are forbidden before whitespace stripping");
            }
            CustomerValues values = index == 0 ? new CustomerValues(raw, "valid") : new CustomerValues("valid", raw);
            return index == 0 ? values.code() : values.name();
        } catch (IllegalArgumentException exception) {
            errors.add(error(row.physicalStartLine(), field, raw, "INVALID_CUSTOMER_FIELD", "必須値・文字数・制御文字を確認してください。",
                    index == 0 ? "顧客コードを1〜50文字で入力し、改行や制御文字を除いてください。"
                            : "顧客名を1〜200文字で入力し、改行や制御文字を除いてください。"));
            return raw;
        }
    }

    private CsvError error(int line, String field, String value, String code, String reason, String correction) {
        return new CsvError(line, field, value, false, code, reason, correction);
    }

    // PostgreSQL text cannot contain NUL. Show its visible control-picture without losing the correction location.
    private String visible(String value) {
        return value.replace('\0', '\u2400');
    }

    // Length-prefixed UTF-8 fields avoid concatenation ambiguity; sorting retains duplicate multiplicity.
    private String fingerprint(List<Row> rows) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, "CUSTOMER:1");
            rows.stream().sorted(Comparator.comparing(Row::customerCode).thenComparing(Row::customerName))
                    .forEach(row -> { add(hash, row.customerCode()); add(hash, row.customerName()); });
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 is unavailable", impossible);
        }
    }

    private void add(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
