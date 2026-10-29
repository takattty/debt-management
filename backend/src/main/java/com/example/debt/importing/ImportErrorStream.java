package com.example.debt.importing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** The resource is read synchronously by MVC; only one bounded database page is buffered. */
final class ImportErrorStream extends InputStream {
    private final ImportStore store;
    private final UUID tenant;
    private final UUID batch;
    private InputStream page = InputStream.nullInputStream();
    private int cursor;
    private boolean first = true;
    private boolean finished;

    ImportErrorStream(ImportStore store, UUID tenant, UUID batch) {
        this.store = store; this.tenant = tenant; this.batch = batch;
    }

    @Override
    public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(value[0]);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) { return 0; }
        int count = page.read(buffer, offset, length);
        if (count != -1 || finished) { return count; }
        var errors = store.errors(tenant, batch, cursor, 1000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var writer = new CsvErrorWriter();
        if (first) { writer.writeHeader(bytes); first = false; }
        for (var error : errors) { writer.writeError(error.value(), bytes); cursor = error.number(); }
        finished = errors.size() < 1000;
        page = new ByteArrayInputStream(bytes.toByteArray());
        return page.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        finished = true;
        page.close();
    }
}
