package com.example.debt.invoice;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.InvoicesApi;
import com.example.debt.generated.model.ReceivableResponse;
import com.example.debt.generated.model.ReceivablesPage;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController implements InvoicesApi {
    private final ReceivablesService service;
    private final CurrentUser current;

    public InvoiceController(ReceivablesService service, CurrentUser current) { this.service = service; this.current = current; }

    @Override
    public ResponseEntity<ReceivablesPage> listReceivables(UUID customerId, LocalDate dueFrom, LocalDate dueTo, String cursor, Integer limit) {
        var page = service.list(current.require().tenantId(), customerId, dueFrom, dueTo, cursor, limit);
        var items = page.items().stream().map(row -> new ReceivableResponse(row.id(), row.customerId(), row.customerCode(), row.customerName(),
                row.invoiceNumber(), row.issueDate(), row.dueDate(), row.amount(), row.amount(), row.version())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReceivablesPage(items, page.nextCursor()));
    }
}
