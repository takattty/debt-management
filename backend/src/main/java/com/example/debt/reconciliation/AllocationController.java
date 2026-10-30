package com.example.debt.reconciliation;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.AllocationsApi;
import com.example.debt.generated.model.AllocationConfirmRequest;
import com.example.debt.generated.model.AllocationOperationResponse;
import com.example.debt.generated.model.AllocationReverseRequest;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AllocationController implements AllocationsApi {
    private final AllocationCommands commands;
    private final CurrentUser current;
    public AllocationController(AllocationCommands commands, CurrentUser current) { this.commands = commands; this.current = current; }

    @Override
    public ResponseEntity<AllocationOperationResponse> confirmAllocation(UUID paymentId, AllocationConfirmRequest request) {
        return response(commands.confirm(current.require(), paymentId, request.getRequestId(), request.getExpectedPaymentVersion(),
                request.getReason(), request.getLines().stream().map(line -> new AllocationCommands.Line(line.getInvoiceId(),
                        line.getExpectedInvoiceVersion(), line.getCashAmount(), line.getFeeAmount())).toList()));
    }
    @Override
    public ResponseEntity<AllocationOperationResponse> reverseAllocation(UUID paymentId, UUID allocationId, AllocationReverseRequest request) {
        return response(commands.reverse(current.require(), paymentId, allocationId, request.getRequestId(), request.getExpectedPaymentVersion(),
                request.getReason(), request.getInvoices().stream().map(line -> new AllocationCommands.Line(line.getInvoiceId(),
                        line.getExpectedInvoiceVersion(), 0, 0)).toList()));
    }
    private ResponseEntity<AllocationOperationResponse> response(AllocationCommands.Operation operation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AllocationOperationResponse(operation.id(), operation.paymentId(),
                operation.requestId(), AllocationOperationResponse.KindEnum.fromValue(operation.kind()), operation.reversesId(), operation.paymentVersion(),
                operation.actorUserId(), operation.reason(), operation.occurredAt().atOffset(ZoneOffset.UTC)));
    }
}
