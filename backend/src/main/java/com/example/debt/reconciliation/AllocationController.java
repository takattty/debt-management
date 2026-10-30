package com.example.debt.reconciliation;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.AllocationsApi;
import com.example.debt.generated.model.AllocationConfirmRequest;
import com.example.debt.generated.model.AllocationHistoryEntry;
import com.example.debt.generated.model.AllocationHistoryLine;
import com.example.debt.generated.model.AllocationHistoryPage;
import com.example.debt.generated.model.AllocationOperationResponse;
import com.example.debt.generated.model.AllocationReverseRequest;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AllocationController implements AllocationsApi {
    private final AllocationCommands commands;
    private final CurrentUser current;
    private final AllocationHistory history;
    public AllocationController(AllocationCommands commands, CurrentUser current, AllocationHistory history) {
        this.commands = commands; this.current = current; this.history = history;
    }

    @Override
    public ResponseEntity<AllocationHistoryPage> listAllocationHistory(UUID paymentId, String cursor, Integer limit) {
        var page = history.list(current.require().tenantId(), paymentId, cursor, limit);
        var entries = page.items().stream().map(entry -> new AllocationHistoryEntry(entry.id(), entry.requestId(),
                AllocationHistoryEntry.KindEnum.fromValue(entry.kind()), entry.reversesId(), entry.reversedByOperationId(),
                Long.toString(entry.paymentVersion()), entry.actorUserId(), entry.actorEmail(), entry.reason(), entry.occurredAt().atOffset(ZoneOffset.UTC),
                entry.lines().stream().map(line -> new AllocationHistoryLine(line.invoiceId(), line.invoiceNumber(), line.customerId(), line.customerName(),
                        Long.toString(line.observedInvoiceVersion()), Long.toString(line.currentInvoiceVersion()), line.cash(), line.fee())).toList())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AllocationHistoryPage(page.paymentId(), Long.toString(page.paymentVersion()), entries, page.nextCursor()));
    }

    @Override
    public ResponseEntity<AllocationOperationResponse> confirmAllocation(UUID paymentId, AllocationConfirmRequest request) {
        return response(commands.confirm(current.require(), paymentId, request.getRequestId(), version(request.getExpectedPaymentVersion()),
                request.getReason(), rows(request.getLines()).stream().map(line -> new AllocationCommands.Line(line.getInvoiceId(),
                        version(line.getExpectedInvoiceVersion()), amount(line.getCashAmount()), amount(line.getFeeAmount()))).toList()));
    }
    @Override
    public ResponseEntity<AllocationOperationResponse> reverseAllocation(UUID paymentId, UUID allocationId, AllocationReverseRequest request) {
        return response(commands.reverse(current.require(), paymentId, allocationId, request.getRequestId(), version(request.getExpectedPaymentVersion()),
                request.getReason(), rows(request.getInvoices()).stream().map(line -> new AllocationCommands.Line(line.getInvoiceId(),
                        version(line.getExpectedInvoiceVersion()), 0, 0)).toList()));
    }
    private <T> List<T> rows(List<T> values) {
        if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Allocation rows must not be null");
        }
        return values;
    }
    private long version(String value) {
        if (value == null || value.length() > 19 || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Expected an exact nonnegative bigint decimal version");
        }
        return Long.parseLong(value);
    }
    private long amount(Long value) {
        if (value == null) { throw new IllegalArgumentException("An allocation amount is required"); }
        return value;
    }
    private ResponseEntity<AllocationOperationResponse> response(AllocationCommands.Operation operation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AllocationOperationResponse(operation.id(), operation.paymentId(),
                operation.requestId(), AllocationOperationResponse.KindEnum.fromValue(operation.kind()), operation.reversesId(), Long.toString(operation.paymentVersion()),
                operation.actorUserId(), operation.reason(), operation.occurredAt().atOffset(ZoneOffset.UTC)));
    }
}
