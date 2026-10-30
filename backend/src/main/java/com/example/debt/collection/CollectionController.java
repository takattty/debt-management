package com.example.debt.collection;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.CollectionsApi;
import com.example.debt.generated.model.CollectionCaseResponse;
import com.example.debt.generated.model.CollectionHistoryEntry;
import com.example.debt.generated.model.CollectionHistoryPage;
import com.example.debt.generated.model.CollectionsPage;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectionController implements CollectionsApi {
    private final CollectionQueries queries;
    private final CurrentUser current;
    public CollectionController(CollectionQueries queries, CurrentUser current) { this.queries = queries; this.current = current; }

    @Override
    public ResponseEntity<CollectionsPage> listCollections(LocalDate asOf, String view, String cursor, Integer limit) {
        var page = queries.list(current.require().tenantId(), asOf, CollectionQueries.View.valueOf(view), cursor, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CollectionsPage(page.items().stream().map(this::response).toList(), page.nextCursor()));
    }
    @Override
    public ResponseEntity<CollectionCaseResponse> getCollection(UUID id, LocalDate asOf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(queries.get(current.require().tenantId(), id, asOf)));
    }
    @Override
    public ResponseEntity<CollectionHistoryPage> listCollectionHistory(UUID id, Long cursor, Integer limit) {
        var page = queries.history(current.require().tenantId(), id, cursor, limit);
        var items = page.items().stream().map(event -> new CollectionHistoryEntry().version(event.version())
                .kind(CollectionHistoryEntry.KindEnum.fromValue(event.kind())).note(event.note()).reason(event.reason())
                .actorUserId(event.actorUserId()).actorLabel(event.actorLabel()).occurredAt(event.occurredAt().atOffset(ZoneOffset.UTC))
                .held(event.held()).holdReason(event.holdReason()).nextActionDate(event.nextActionDate())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollectionHistoryPage(items, page.nextCursor()));
    }

    private CollectionCaseResponse response(CollectionQueries.Case row) {
        return new CollectionCaseResponse().invoiceId(row.invoiceId()).customerId(row.customerId()).customerCode(row.customerCode()).customerName(row.customerName())
                .invoiceNumber(row.invoiceNumber()).issueDate(row.issueDate()).dueDate(row.dueDate())
                .invoiceStatus(CollectionCaseResponse.InvoiceStatusEnum.fromValue(row.invoiceStatus())).invoiceVersion(row.invoiceVersion())
                .amount(row.balance().amount().longValueExact()).cashAllocated(row.balance().cash().longValueExact()).explicitFees(row.balance().fee().longValueExact())
                .outstandingAmount(row.effectiveOutstanding().longValueExact()).asOf(row.asOf()).daysOverdue(row.daysOverdue()).eligible(row.eligible())
                .held(row.held()).holdReason(row.holdReason()).latestNote(row.latestNote()).nextActionDate(row.nextActionDate()).version(row.version());
    }
}
