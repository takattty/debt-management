package com.example.debt.importing;

import com.example.debt.payment.PaymentValues;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PaymentCsv {
    public static final String HEADER = "transaction_id,transfer_date,payer_name,amount\r\n";
    public record Row(int recordNumber, int rowNumber, String transactionId, String transferDate,
                      String payerName, String amount, boolean valid) { }
    public record Preview(List<Row> rows, List<CsvError> errors, String fingerprint) {
        public Preview { rows = List.copyOf(rows); errors = List.copyOf(errors); }
    }

    public Preview parse(InputStream source, Function<Set<String>, Set<String>> findExisting) {
        CsvResult parsed = new CsvParser().parse(source);
        List<CsvError> errors = new ArrayList<>(parsed.errors());
        List<Row> rows = new ArrayList<>();
        Set<Integer> validIds = new HashSet<>();
        if (errors.isEmpty() && (parsed.rows().isEmpty() || !parsed.rows().getFirst().cells().equals(
                List.of("transaction_id", "transfer_date", "payer_name", "amount")))) {
            errors.add(error(1, "header", parsed.rows().isEmpty() ? "" : String.join(",", parsed.rows().getFirst().cells()),
                    "INVALID_HEADER", "ヘッダーが固定形式と異なります。", "テンプレートの列名と順序を使用してください。"));
        } else if (errors.isEmpty()) {
            for (CsvRow row : parsed.rows().subList(1, parsed.rows().size())) {
                if (row.cells().size() != 4) {
                    errors.add(error(row.physicalStartLine(), "columns", String.join(",", row.cells()), "COLUMN_COUNT",
                            "列数が4列ではありません。", "テンプレートに従って4列を入力してください。"));
                }
                int beforeId = errors.size();
                String id = field(row, 0, "transaction_id", errors, () -> PaymentValues.text(raw(row, 0), 100));
                if (errors.size() == beforeId) { validIds.add(row.physicalStartLine()); }
                String date = field(row, 1, "transfer_date", errors, () -> PaymentValues.date(raw(row, 1)).toString());
                String payer = field(row, 2, "payer_name", errors, () -> PaymentValues.text(raw(row, 2), 200));
                String amount = field(row, 3, "amount", errors, () -> Long.toString(PaymentValues.amount(raw(row, 3))));
                rows.add(new Row(rows.size() + 1, row.physicalStartLine(), id, date, payer, amount, false));
            }
            if (rows.isEmpty()) {
                errors.add(error(1, "file", "", "EMPTY_IMPORT", "データ行がありません。", "ヘッダーの次の行から入金を入力してください。"));
            }
        }
        var counts = new HashMap<String, Integer>();
        rows.stream().filter(row -> validIds.contains(row.rowNumber())).forEach(row -> counts.merge(row.transactionId(), 1, Integer::sum));
        Set<String> existing = counts.isEmpty() ? Set.of() : findExisting.apply(counts.keySet());
        for (Row row : rows) {
            if (validIds.contains(row.rowNumber()) && counts.get(row.transactionId()) > 1) {
                errors.add(error(row.rowNumber(), "transaction_id", row.transactionId(), "DUPLICATE_TRANSACTION_ID",
                        "同じCSVに同一取引IDが複数あります。", "取引IDを重複させず1行だけにしてください。"));
            }
            if (validIds.contains(row.rowNumber()) && existing.contains(row.transactionId())) {
                errors.add(error(row.rowNumber(), "transaction_id", row.transactionId(), "PAYMENT_EXISTS",
                        "取引IDは登録済みです。消込取消後も再利用できません。", "既存入金を確認し、未登録の取引だけを入力してください。"));
            }
        }
        Set<Integer> invalid = new HashSet<>();
        errors.forEach(error -> invalid.add(error.rowNumber()));
        rows = rows.stream().map(row -> new Row(row.recordNumber(), row.rowNumber(), visible(row.transactionId()),
                visible(row.transferDate()), visible(row.payerName()), visible(row.amount()), !invalid.contains(row.rowNumber()))).toList();
        errors = errors.stream().map(error -> new CsvError(error.rowNumber(), error.field(), visible(error.inputValue()),
                error.inputTruncated(), error.reasonCode(), error.reason(), error.correction())).toList();
        return new Preview(rows, errors, fingerprint(rows));
    }

    private String field(CsvRow row, int index, String field, List<CsvError> errors, Supplier<String> parse) {
        try { return parse.get(); } catch (IllegalArgumentException invalid) {
            String correction = switch (field) {
                case "transaction_id" -> "取引IDを1〜100文字で入力し、改行や制御文字を除いてください。";
                case "payer_name" -> "振込名義を1〜200文字で入力し、改行や制御文字を除いてください。";
                case "amount" -> "入金額を1〜9007199254740991の円整数で入力してください。0円・出金・返金は対象外です。";
                default -> "実在する振込日をYYYY-MM-DD（年0001〜9999）で入力してください。";
            };
            errors.add(error(row.physicalStartLine(), field, raw(row, index), "INVALID_" + field.toUpperCase(java.util.Locale.ROOT),
                    "入力形式・値の範囲を確認してください。", correction));
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
            add(hash, "PAYMENT:1");
            rows.stream().sorted(Comparator.comparing(Row::transactionId).thenComparing(Row::transferDate)
                    .thenComparing(Row::payerName).thenComparing(Row::amount)).forEach(row -> {
                        add(hash, row.transactionId()); add(hash, row.transferDate()); add(hash, row.payerName()); add(hash, row.amount());
                    });
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 is unavailable", impossible); }
    }
    private void add(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
    }
}
