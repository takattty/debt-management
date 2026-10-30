package com.example.debt.invoice;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.InvoicesApi;
import com.example.debt.generated.model.ReceivableResponse;
import com.example.debt.generated.model.ReceivablesPage;
import com.example.debt.generated.model.InvoiceCorrectionRequest;
import com.example.debt.generated.model.InvoiceCancelRequest;
import com.example.debt.generated.model.InvoiceResponse;
import com.example.debt.generated.model.InvoiceHistoryPage;
import com.example.debt.generated.model.InvoiceHistoryEntry;
import com.example.debt.generated.model.InvoiceHistorySnapshot;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController implements InvoicesApi {
    private final ReceivablesService service;
    private final CurrentUser current;
    private final InvoiceQueries queries;
    private final InvoiceMutationService mutations;

    public InvoiceController(ReceivablesService service, CurrentUser current, InvoiceQueries queries, InvoiceMutationService mutations) {
        this.service = service; this.current = current; this.queries = queries; this.mutations = mutations;
    }

    @Override
    public ResponseEntity<InvoiceResponse> getInvoice(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(queries.get(current.require().tenantId(), id, false)));
    }

    @Override
    public ResponseEntity<InvoiceResponse> correctInvoice(UUID id, InvoiceCorrectionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(mutations.correct(current.require(), id, request.getVersion(),
                request.getCustomerId(), request.getIssueDate(), request.getDueDate(), request.getAmount(), request.getReason())));
    }

    @Override
    public ResponseEntity<InvoiceResponse> cancelInvoice(UUID id, InvoiceCancelRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(mutations.cancel(current.require(), id, request.getVersion(), request.getReason())));
    }

    @Override
    public ResponseEntity<InvoiceHistoryPage> listInvoiceHistory(UUID id, Long cursor, Integer limit) {
        var page = queries.history(current.require().tenantId(), id, cursor, limit);
        var items = page.items().stream().map(row -> {
            var facts = row.snapshot();
            var snapshot = new InvoiceHistorySnapshot(facts.customerId(), facts.customerCode(), facts.customerName(), facts.invoiceNumber(),
                    facts.issueDate(), facts.dueDate(), facts.amount(), InvoiceHistorySnapshot.StatusEnum.fromValue(facts.status()), facts.sourceBatchId(), facts.version());
            return new InvoiceHistoryEntry(row.revision(), InvoiceHistoryEntry.OperationEnum.fromValue(row.operation()), row.reason(), row.actorUserId(),
                    row.occurredAt().atOffset(ZoneOffset.UTC), snapshot, row.actorEmail());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InvoiceHistoryPage(items, page.nextCursor()));
    }

    private InvoiceResponse response(InvoiceState state) {
        return new InvoiceResponse(state.id(), state.customerId(), state.customerCode(), state.customerName(), state.invoiceNumber(), state.issueDate(), state.dueDate(),
                state.amount(), state.status().equals("ACTIVE") ? state.amount() : 0, state.version(), InvoiceResponse.StatusEnum.fromValue(state.status()),
                state.createdAt().atOffset(ZoneOffset.UTC), state.updatedAt().atOffset(ZoneOffset.UTC),
                state.cancelledAt() == null ? null : state.cancelledAt().atOffset(ZoneOffset.UTC));
    }

    @Override
    public ResponseEntity<ReceivablesPage> listReceivables(UUID customerId, LocalDate dueFrom, LocalDate dueTo, String cursor, Integer limit, String status) {
        var page = service.list(current.require().tenantId(), customerId, dueFrom, dueTo, cursor, limit, status);
        var items = page.items().stream().map(row -> new ReceivableResponse(row.id(), row.customerId(), row.customerCode(), row.customerName(),
                row.invoiceNumber(), row.issueDate(), row.dueDate(), row.amount(), row.status().equals("ACTIVE") ? row.amount() : 0,
                row.version(), ReceivableResponse.StatusEnum.fromValue(row.status()))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReceivablesPage(items, page.nextCursor()));
    }
}
