package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InvoiceCsvTest {
    private static final InvoiceCsv.CustomerReference CUSTOMER = new InvoiceCsv.CustomerReference(UUID.randomUUID(), 7, "架空商事");

    @Test
    void canonicalizesBomNewlinesOrderAndAmountWhileKeepingEveryDuplicate() {
        var first = parse(InvoiceCsv.HEADER + "C001, INV-1 ,2026-10-29,2026-10-30,000100\r\nC001,INV-2,2026-10-29,2026-10-29,200\r\n");
        var second = parse("\ufeff" + InvoiceCsv.HEADER.replace("\r\n", "\n")
                + "C001,INV-2,2026-10-29,2026-10-29,200\nC001,INV-1,2026-10-29,2026-10-30,100\n");
        assertThat(first.errors()).isEmpty();
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.rows().getFirst().amount()).isEqualTo("100");
        assertThat(first.customers().get("C001").version()).isEqualTo(7);
        var duplicate = parse(InvoiceCsv.HEADER + "C001,INV-1,2026-10-29,2026-10-30,100\nC001,INV-1,2026-10-29,2026-10-30,100\n");
        assertThat(duplicate.errors()).extracting(CsvError::reasonCode).containsExactly("DUPLICATE_INVOICE_NUMBER", "DUPLICATE_INVOICE_NUMBER");
        assertThat(duplicate.fingerprint()).isNotEqualTo(parse(InvoiceCsv.HEADER + "C001,INV-1,2026-10-29,2026-10-30,100\n").fingerprint());
    }

    @Test
    void collectsAllFieldsAndPhysicalLinesAndRejectsRawControlsBeforeStripping() {
        var preview = parse(InvoiceCsv.HEADER + "C001,INV-1,2026-10-29,2026-10-28,0\n"
                + "\"C001\n\",,2026-02-29,bad,-1\nC001,INV\0,2026-10-29,2026-10-29,1\n");
        assertThat(preview.errors()).extracting(CsvError::field).contains("amount", "due_date", "customer_code", "invoice_number", "issue_date");
        assertThat(preview.rows()).extracting(InvoiceCsv.Row::rowNumber).containsExactly(2, 3, 5);
        assertThat(preview.rows()).noneMatch(InvoiceCsv.Row::valid);
        assertThat(preview.rows().getLast().invoiceNumber()).isEqualTo("INV␀");
        assertThat(preview.errors()).filteredOn(error -> error.rowNumber() == 5).singleElement()
                .extracting(CsvError::inputValue).isEqualTo("INV␀");
    }

    @Test
    void detectsMissingCustomerAndEveryExistingNumberWithTwoBulkLookups() {
        AtomicInteger customerQueries = new AtomicInteger();
        AtomicInteger numberQueries = new AtomicInteger();
        var preview = new InvoiceCsv().parse(bytes(InvoiceCsv.HEADER
                + "C001,INV-1,2026-10-29,2026-10-29,1\nMISSING,INV-2,2026-10-29,2026-10-29,2\n"), codes -> {
                    customerQueries.incrementAndGet(); assertThat(codes).containsExactlyInAnyOrder("C001", "MISSING");
                    return Map.of("C001", CUSTOMER);
                }, numbers -> { numberQueries.incrementAndGet(); return Set.of("INV-1"); });
        assertThat(preview.errors()).extracting(CsvError::reasonCode).containsExactly("INVOICE_EXISTS", "CUSTOMER_NOT_FOUND");
        assertThat(customerQueries.get()).isEqualTo(1);
        assertThat(numberQueries.get()).isEqualTo(1);
    }

    @Test
    void rejectsWrongHeaderAndEmptyDataWithoutReferenceQueries() {
        assertThat(parse("invoice_number,customer_code,issue_date,due_date,amount\n").errors().getFirst().reasonCode()).isEqualTo("INVALID_HEADER");
        assertThat(parse(InvoiceCsv.HEADER).errors().getFirst().reasonCode()).isEqualTo("EMPTY_IMPORT");
        assertThat(parse(InvoiceCsv.HEADER + "C001,INV,2026-10-29,2026-10-29,1,extra\n").errors())
                .extracting(CsvError::reasonCode).contains("COLUMN_COUNT");
    }

    private InvoiceCsv.Preview parse(String csv) { return new InvoiceCsv().parse(bytes(csv), codes -> Map.of("C001", CUSTOMER), numbers -> Set.of()); }
    private ByteArrayInputStream bytes(String csv) { return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)); }
}
