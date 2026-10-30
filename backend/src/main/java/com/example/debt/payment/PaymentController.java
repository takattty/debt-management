package com.example.debt.payment;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.PaymentsApi;
import com.example.debt.generated.model.PaymentResponse;
import com.example.debt.generated.model.PaymentsPage;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController implements PaymentsApi {
    private final PaymentsService payments;
    private final CurrentUser current;

    public PaymentController(PaymentsService payments, CurrentUser current) { this.payments = payments; this.current = current; }

    @Override
    public ResponseEntity<PaymentsPage> listPayments(LocalDate dateFrom, LocalDate dateTo, String cursor, Integer limit) {
        var page = payments.list(current.require().tenantId(), dateFrom, dateTo, cursor, limit);
        var items = page.items().stream().map(row -> new PaymentResponse(row.id(), row.transactionId(), row.transferDate(),
                row.payerName(), row.amount(), row.unallocated(), row.sourceBatchId(), row.createdByUserId(), row.createdAt().atOffset(ZoneOffset.UTC))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PaymentsPage(items, page.nextCursor()));
    }
}
