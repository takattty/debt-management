package com.example.debt.customer;

import java.util.UUID;

public record Customer(UUID id, String code, String name, long version) {}
