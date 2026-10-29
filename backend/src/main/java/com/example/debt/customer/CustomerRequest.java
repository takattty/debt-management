package com.example.debt.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 200) String name) {
    public CustomerValues values() {
        return new CustomerValues(code, name);
    }
}
