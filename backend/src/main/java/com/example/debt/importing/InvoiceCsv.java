package com.example.debt.importing;

import com.example.debt.customer.CustomerValues;
import com.example.debt.invoice.InvoiceValidationException;
import com.example.debt.invoice.InvoiceValues;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fixed invoice facts only: a preview never creates or changes an invoice. */
public final class InvoiceCsv {
    public static final String HEADER = "customer_code,invoice_number,issue_date,due_date,amount\r\n";
    public record CustomerReference(UUID id, long version, String name) { }
    public record Row(int recordNumber, int rowNumber, String customerCode, String customerName,
                      String invoiceNumber, String issueDate, String dueDate, String amount, boolean valid) { }
    public record Preview(List<Row> rows, List<CsvError> errors, String fingerprint, Map<String, CustomerReference> customers) {
        public Preview { rows = List.copyOf(rows); errors = List.copyOf(errors); customers = Map.copyOf(customers); }
    }

    public Preview parse(InputStream source, Function<Set<String>, Map<String, CustomerReference>> findCustomers,
                         Function<Set<String>, Set<String>> findNumbers) {
        CsvResult parsed = new CsvParser().parse(source);
        List<CsvError> errors = new ArrayList<>(parsed.errors());
        List<Row> rows = new ArrayList<>();
        Set<Integer> codesValid = new HashSet<>();
        Set<Integer> numbersValid = new HashSet<>();
        if (errors.isEmpty() && (parsed.rows().isEmpty() || !parsed.rows().getFirst().cells().equals(
                List.of("customer_code", "invoice_number", "issue_date", "due_date", "amount")))) {
            errors.add(error(1, "header", parsed.rows().isEmpty() ? "" : String.join(",", parsed.rows().getFirst().cells()),
                    "INVALID_HEADER", "ヘッダーが固定形式と異なります。", "テンプレートの列名と順序を使用してください。"));
        } else if (errors.isEmpty()) {
            for (CsvRow row : parsed.rows().subList(1, parsed.rows().size())) {
                int before = errors.size();
                if (row.cells().size() != 5) {
                    errors.add(error(row.physicalStartLine(), "columns", String.join(",", row.cells()), "COLUMN_COUNT",
                            "列数が5列ではありません。", "テンプレートに従って5列を入力してください。"));
                }
                int fieldBefore = errors.size();
                String code = field(row, 0, "customer_code", errors, () -> {
                    String raw = raw(row, 0);
                    if (raw.codePoints().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException(); }
                    return new CustomerValues(raw, "valid").code();
                });
                if (errors.size() == fieldBefore) { codesValid.add(row.physicalStartLine()); }
                fieldBefore = errors.size();
                String number = field(row, 1, "invoice_number", errors, () -> InvoiceValues.parseNumber(raw(row, 1)));
                if (errors.size() == fieldBefore) { numbersValid.add(row.physicalStartLine()); }
                String issue = field(row, 2, "issue_date", errors, () -> InvoiceValues.parseDate(raw(row, 2), "issue_date").toString());
                String due = field(row, 3, "due_date", errors, () -> InvoiceValues.parseDate(raw(row, 3), "due_date").toString());
                String amount = field(row, 4, "amount", errors, () -> Long.toString(InvoiceValues.parseAmount(raw(row, 4))));
                try {
                    if (InvoiceValues.parseDate(due, "due_date").isBefore(InvoiceValues.parseDate(issue, "issue_date"))) {
                        errors.add(error(row.physicalStartLine(), "due_date", raw(row, 3), "DUE_BEFORE_ISSUE",
                                "支払期日が請求日より前です。", "支払期日を請求日以降にしてください。"));
                    }
                } catch (InvoiceValidationException alreadyReported) { /* Each invalid date already has a field error. */ }
                rows.add(new Row(rows.size() + 1, row.physicalStartLine(), code, "", number, issue, due, amount, errors.size() == before));
            }
            if (rows.isEmpty()) {
                errors.add(error(1, "file", "", "EMPTY_IMPORT", "データ行がありません。", "ヘッダーの次の行から請求を入力してください。"));
            }
        }
        Set<String> codes = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : rows) {
            if (codesValid.contains(row.rowNumber())) { codes.add(row.customerCode()); }
            if (numbersValid.contains(row.rowNumber())) {
                numbers.add(row.invoiceNumber()); counts.merge(row.invoiceNumber(), 1, Integer::sum);
            }
        }
        Map<String, CustomerReference> customers = codes.isEmpty() ? Map.of() : findCustomers.apply(codes);
        Set<String> existing = numbers.isEmpty() ? Set.of() : findNumbers.apply(numbers);
        for (Row row : rows) {
            if (codesValid.contains(row.rowNumber()) && !customers.containsKey(row.customerCode())) {
                errors.add(error(row.rowNumber(), "customer_code", row.customerCode(), "CUSTOMER_NOT_FOUND",
                        "顧客コードが登録されていません。", "この契約企業に顧客を先に登録し、CSVを再プレビューしてください。"));
            }
            if (numbersValid.contains(row.rowNumber()) && counts.get(row.invoiceNumber()) > 1) {
                errors.add(error(row.rowNumber(), "invoice_number", row.invoiceNumber(), "DUPLICATE_INVOICE_NUMBER",
                        "同じCSVに同一請求番号が複数あります。", "請求番号を重複させず1行だけにしてください。"));
            }
            if (numbersValid.contains(row.rowNumber()) && existing.contains(row.invoiceNumber())) {
                errors.add(error(row.rowNumber(), "invoice_number", row.invoiceNumber(), "INVOICE_EXISTS",
                        "請求番号は登録済みです。取消済みの番号も再利用できません。", "既存請求を確認し、別の請求番号を使用してください。"));
            }
        }
        Set<Integer> invalid = new HashSet<>();
        errors.forEach(error -> invalid.add(error.rowNumber()));
        rows = rows.stream().map(row -> new Row(row.recordNumber(), row.rowNumber(), visible(row.customerCode()),
                customers.containsKey(row.customerCode()) ? customers.get(row.customerCode()).name() : "",
                visible(row.invoiceNumber()), visible(row.issueDate()), visible(row.dueDate()), visible(row.amount()),
                !invalid.contains(row.rowNumber()))).toList();
        errors = errors.stream().map(error -> new CsvError(error.rowNumber(), error.field(), visible(error.inputValue()),
                error.inputTruncated(), error.reasonCode(), error.reason(), error.correction())).toList();
        return new Preview(rows, errors, fingerprint(rows), customers);
    }

    private String field(CsvRow row, int index, String field, List<CsvError> errors, Supplier<String> parse) {
        try { return parse.get(); } catch (IllegalArgumentException invalid) {
            String code = invalid instanceof InvoiceValidationException domain ? domain.reasonCode() : "INVALID_CUSTOMER_FIELD";
            String correction = switch (field) {
                case "customer_code" -> "顧客コードを1〜50文字で入力し、改行や制御文字を除いてください。";
                case "invoice_number" -> "請求番号を1〜100文字で入力し、改行や制御文字を除いてください。";
                case "amount" -> "税込の円整数を1〜9007199254740991で入力してください。小数・桁区切りは使用できません。";
                default -> "実在する日付をYYYY-MM-DD（年0001〜9999）で入力してください。";
            };
            errors.add(error(row.physicalStartLine(), field, raw(row, index), code, "入力形式・値の範囲を確認してください。", correction));
            return raw(row, index);
        }
    }

    private String raw(CsvRow row, int index) { return row.cells().size() > index ? row.cells().get(index) : ""; }
    private String visible(String value) { return value.replace('\0', '\u2400'); }
    private CsvError error(int line, String field, String value, String code, String reason, String correction) {
        return new CsvError(line, field, value, false, code, reason, correction);
    }
    private String fingerprint(List<Row> rows) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, "INVOICE:1");
            rows.stream().sorted(Comparator.comparing(Row::invoiceNumber).thenComparing(Row::customerCode)
                    .thenComparing(Row::issueDate).thenComparing(Row::dueDate).thenComparing(Row::amount)).forEach(row -> {
                        add(hash, row.customerCode()); add(hash, row.invoiceNumber()); add(hash, row.issueDate());
                        add(hash, row.dueDate()); add(hash, row.amount());
                    });
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 is unavailable", impossible); }
    }
    private void add(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
    }
}
