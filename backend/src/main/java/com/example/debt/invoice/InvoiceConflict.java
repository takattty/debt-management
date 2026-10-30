package com.example.debt.invoice;

public final class InvoiceConflict extends RuntimeException {
    private final String code;
    public InvoiceConflict(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
