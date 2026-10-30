package com.example.debt.payment;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.PayerAliasesApi;
import com.example.debt.generated.model.PayerAliasCreateRequest;
import com.example.debt.generated.model.PayerAliasDeactivateRequest;
import com.example.debt.generated.model.PayerAliasResponse;
import com.example.debt.generated.model.PayerAliasesPage;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayerAliasController implements PayerAliasesApi {
    private final PayerAliases aliases;
    private final CurrentUser current;
    public PayerAliasController(PayerAliases aliases, CurrentUser current) { this.aliases = aliases; this.current = current; }

    @Override
    public ResponseEntity<PayerAliasesPage> listPayerAliases(UUID cursor, Integer limit) {
        var page = aliases.list(current.require().tenantId(), cursor, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PayerAliasesPage(page.items().stream().map(this::response).toList(), page.nextCursor()));
    }

    @Override
    public ResponseEntity<PayerAliasResponse> getPayerAlias(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(aliases.get(current.require().tenantId(), id)));
    }

    @Override
    public ResponseEntity<PayerAliasResponse> createPayerAlias(PayerAliasCreateRequest request) {
        var row = aliases.register(current.require(), request.getPayerName(), request.getCustomerId());
        return ResponseEntity.created(URI.create("/api/payer-aliases/" + row.id())).cacheControl(CacheControl.noStore()).body(response(row));
    }

    @Override
    public ResponseEntity<PayerAliasResponse> deactivatePayerAlias(UUID id, PayerAliasDeactivateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(aliases.deactivate(current.require(), id, request.getVersion(), request.getReason())));
    }

    private PayerAliasResponse response(PayerAliases.Row row) {
        return new PayerAliasResponse(row.id(), row.payerName(), row.customerId(), row.active(), row.version(),
                row.createdByUserId(), row.createdAt().atOffset(ZoneOffset.UTC), row.deactivatedByUserId(),
                row.deactivatedAt() == null ? null : row.deactivatedAt().atOffset(ZoneOffset.UTC), row.deactivationReason());
    }
}
