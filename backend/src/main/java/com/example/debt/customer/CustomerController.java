package com.example.debt.customer;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.CustomersApi;
import com.example.debt.generated.model.CustomerCreateRequest;
import com.example.debt.generated.model.CustomerResponse;
import com.example.debt.generated.model.CustomerUpdateRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController implements CustomersApi {
    private final CustomerService service;
    private final CurrentUser currentUser;

    public CustomerController(CustomerService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<List<CustomerResponse>> listCustomers() {
        var user = currentUser.require();
        return ResponseEntity.ok(service.list(user.tenantId()).stream().map(this::response).toList());
    }

    @Override
    public ResponseEntity<CustomerResponse> createCustomer(CustomerCreateRequest request) {
        var user = currentUser.require();
        Customer customer = service.create(user.tenantId(), user.userId(),
                new CustomerValues(request.getCode(), request.getName()));
        return ResponseEntity.created(URI.create("/api/customers/" + customer.id())).body(response(customer));
    }

    @Override
    public ResponseEntity<CustomerResponse> updateCustomer(UUID id, CustomerUpdateRequest request) {
        var user = currentUser.require();
        Customer customer = service.update(user.tenantId(), user.userId(), id,
                new CustomerValues(request.getCode(), request.getName()), request.getVersion());
        return ResponseEntity.ok(response(customer));
    }

    private CustomerResponse response(Customer customer) {
        return new CustomerResponse(customer.id(), customer.code(), customer.name(), customer.version());
    }
}
