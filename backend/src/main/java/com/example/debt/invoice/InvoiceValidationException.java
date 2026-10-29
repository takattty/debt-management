package com.example.debt.invoice;

/** A field-level domain failure; the caller retains the input separately for correction guidance. */
public final class InvoiceValidationException extends IllegalArgumentException {
    private final String field;
    private final String reasonCode;

    public InvoiceValidationException(String field, String reasonCode, String message) {
        super(message);
        this.field = field;
        this.reasonCode = reasonCode;
    }

    public String field() {
        return field;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
