package com.example.debt.customer;

import com.example.debt.access.TenantUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    List<Customer> list(@AuthenticationPrincipal TenantUser user) {
        return service.list(user.tenantId());
    }

    @PostMapping
    ResponseEntity<Customer> create(@AuthenticationPrincipal TenantUser user,
            @Valid @RequestBody CustomerRequest request) {
        Customer customer = service.create(user.tenantId(), user.getUsername(), request.values());
        return ResponseEntity.created(URI.create("/api/customers/" + customer.id())).body(customer);
    }

    @PutMapping("/{id}")
    Customer update(@AuthenticationPrincipal TenantUser user, @PathVariable UUID id,
            @Valid @RequestBody CustomerUpdateRequest request) {
        return service.update(user.tenantId(), user.getUsername(), id, request.values(), request.version());
    }
}
