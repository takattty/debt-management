package com.example.debt.reconciliation;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.PaymentCandidatesApi;
import com.example.debt.generated.model.ApiError;
import com.example.debt.generated.model.PaymentCandidateResponse;
import com.example.debt.generated.model.PaymentCandidatesPage;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentCandidatesController implements PaymentCandidatesApi {
    private final PaymentCandidates candidates;
    private final CurrentUser current;
    public PaymentCandidatesController(PaymentCandidates candidates, CurrentUser current) { this.candidates = candidates; this.current = current; }

    @Override
    public ResponseEntity<PaymentCandidatesPage> listPaymentCandidates(UUID id, String cursor, Integer limit) {
        var page = candidates.list(current.require().tenantId(), id, cursor, limit);
        var payment = page.payment();
        var items = page.items().stream().map(row -> new PaymentCandidateResponse(row.invoiceId(), row.customerId(), row.customerCode(), row.customerName(),
                row.invoiceNumber(), row.issueDate(), row.dueDate(), row.amount(), row.outstanding(), Long.toString(row.version()),
                row.manualPayerMatch(), row.exactAmountMatch())).toList();
        String payerStatus = page.matchedCustomers() == 0 ? "UNREGISTERED" : page.matchedCustomers() == 1 ? "UNIQUE" : "MULTIPLE_CUSTOMERS";
        var response = new PaymentCandidatesPage(payment.id(), payment.payerName(), payment.transferDate(), payment.unallocated(), Long.toString(payment.version()),
                PaymentCandidatesPage.PayerMatchStatusEnum.fromValue(payerStatus), page.matchedCustomers(),
                PaymentCandidatesPage.CashCandidateStatusEnum.fromValue(payment.unallocated() == 0 ? "NO_UNALLOCATED_CASH" : "AVAILABLE"), true, items, page.nextCursor());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @ExceptionHandler(PaymentCandidates.Missing.class)
    ResponseEntity<ApiError> missing(PaymentCandidates.Missing missing) {
        return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).body(new ApiError("NOT_FOUND", "入金が見つかりません。", List.of()));
    }
}
