package com.example.debt.customer;

public class CustomerConflict extends RuntimeException {
    public CustomerConflict() {
        super("Customer was changed by another request. Reload and retry.");
    }
}
