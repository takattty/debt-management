package com.example.debt.collection;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CollectionDrafts {
    private final CollectionQueries queries;
    public record Draft(CollectionQueries.Case facts, String subject, String body) { }
    public CollectionDrafts(CollectionQueries queries) { this.queries = queries; }
    public Draft preview(UUID tenant, UUID invoice, LocalDate asOf) {
        var row = queries.get(tenant, invoice, asOf);
        if (!row.eligible() || row.effectiveOutstanding().signum() <= 0) { throw new CollectionActions.Conflict("COLLECTION_NOT_ELIGIBLE"); }
        String amount = NumberFormat.getIntegerInstance(Locale.JAPAN).format(row.effectiveOutstanding());
        return new Draft(row, "お支払い状況の確認：請求書 " + row.invoiceNumber(), row.customerName() + " 御中\n\n"
                + "請求書 " + row.invoiceNumber() + " について、お支払い状況の確認をお願いいたします。\n"
                + "お支払期日：" + row.dueDate() + "\n確認基準日：" + row.asOf() + "\n現在の未回収額：" + amount + " 円\n\n"
                + "すでにお支払い済みの場合は、行き違いをご容赦ください。お支払い状況をお知らせいただけますと幸いです。");
    }
}
