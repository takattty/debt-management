package com.example.debt.collection;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.CollectionsApi;
import com.example.debt.generated.model.CollectionCaseResponse;
import com.example.debt.generated.model.CollectionHistoryEntry;
import com.example.debt.generated.model.CollectionHistoryPage;
import com.example.debt.generated.model.CollectionsPage;
import com.example.debt.generated.model.CollectionActionRequest;
import com.example.debt.generated.model.CollectionReasonRequest;
import com.example.debt.generated.model.CollectionStateResponse;
import com.example.debt.generated.model.CollectionDraftResponse;
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
    private final CollectionActions actions;
    private final CollectionDrafts drafts;
    public CollectionController(CollectionQueries queries, CurrentUser current, CollectionActions actions, CollectionDrafts drafts) {
        this.queries = queries; this.current = current; this.actions = actions; this.drafts = drafts;
    }

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
        var items = page.items().stream().map(this::event).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollectionHistoryPage(items, page.nextCursor()));
    }

    @Override
    public ResponseEntity<CollectionStateResponse> recordCollectionAction(UUID id, CollectionActionRequest request) {
        return state(id, actions.record(current.require(), id, request.getVersion(), request.getNote(), request.getNextActionDate()));
    }
    @Override
    public ResponseEntity<CollectionStateResponse> holdCollection(UUID id, CollectionReasonRequest request) {
        return state(id, actions.hold(current.require(), id, request.getVersion(), request.getReason()));
    }
    @Override
    public ResponseEntity<CollectionStateResponse> releaseCollection(UUID id, CollectionReasonRequest request) {
        return state(id, actions.release(current.require(), id, request.getVersion(), request.getReason()));
    }
    @Override
    public ResponseEntity<CollectionDraftResponse> previewCollectionDraft(UUID id, LocalDate asOf) {
        var draft = drafts.preview(current.require().tenantId(), id, asOf);
        var facts = draft.facts();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollectionDraftResponse().invoiceId(id).asOf(asOf)
                .customerName(facts.customerName()).outstandingAmount(facts.balance().outstanding().longValueExact())
                .subject(draft.subject()).body(draft.body()).held(facts.held()).holdReason(facts.holdReason()));
    }
    private ResponseEntity<CollectionStateResponse> state(UUID id, CollectionQueries.Event event) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollectionStateResponse().invoiceId(id).version(event.version())
                .held(event.held()).holdReason(event.holdReason()).latestNote(event.note()).nextActionDate(event.nextActionDate()).event(event(event)));
    }
    private CollectionHistoryEntry event(CollectionQueries.Event event) {
        return new CollectionHistoryEntry().version(event.version()).kind(CollectionHistoryEntry.KindEnum.fromValue(event.kind())).note(event.note()).reason(event.reason())
                .actorUserId(event.actorUserId()).actorLabel(event.actorLabel()).occurredAt(event.occurredAt().atOffset(ZoneOffset.UTC))
                .held(event.held()).holdReason(event.holdReason()).nextActionDate(event.nextActionDate());
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
