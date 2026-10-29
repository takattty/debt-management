package com.example.debt.importing;

import com.example.debt.access.CurrentUser;
import com.example.debt.generated.api.ImportsApi;
import com.example.debt.generated.model.ImportBatchResponse;
import com.example.debt.generated.model.ImportErrorResponse;
import com.example.debt.generated.model.ImportErrorsPage;
import com.example.debt.generated.model.ImportRowResponse;
import com.example.debt.generated.model.ImportRowsPage;
import com.example.debt.generated.model.ImportConfirmRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.ServletException;

@RestController
public class ImportController implements ImportsApi {
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private final CustomerImportService service;
    private final ImportStore store;
    private final CurrentUser current;
    private final ConfirmCustomerImport confirmation;

    public ImportController(CustomerImportService service, ImportStore store, CurrentUser current, ConfirmCustomerImport confirmation) {
        this.service = service; this.store = store; this.current = current; this.confirmation = confirmation;
    }

    @Override
    public ResponseEntity<Resource> downloadCustomerImportTemplate() {
        current.require();
        return csv(new ByteArrayResource(("\ufeff" + CustomerCsv.HEADER).getBytes(StandardCharsets.UTF_8)), "customers-template.csv");
    }

    @Override
    public ResponseEntity<ImportBatchResponse> previewCustomerImport(MultipartFile file) {
        var user = current.require();
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        boolean oneFile;
        try {
            // RequestContextHolder can expose the original servlet request before Spring's multipart wrapper.
            oneFile = multipart != null ? multipart.getMultiFileMap().size() == 1 && multipart.getFiles("file").size() == 1
                    : request.getParts().size() == 1 && request.getParts().iterator().next().getName().equals("file");
        } catch (IOException | ServletException exception) {
            throw new IllegalArgumentException("Cannot read multipart metadata", exception);
        }
        if (!oneFile || !request.getParameterMap().isEmpty()) {
            throw new IllegalArgumentException("Exactly one file and no extra parameters are allowed");
        }
        try (var source = file.getInputStream()) {
            ImportBatch batch = service.preview(user, source);
            return ResponseEntity.created(URI.create("/api/imports/" + batch.id())).cacheControl(CacheControl.noStore()).body(response(batch));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read the uploaded file", exception);
        }
    }

    @Override
    public ResponseEntity<ImportBatchResponse> getImportBatch(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(store.get(current.require().tenantId(), id, false)));
    }

    @Override
    public ResponseEntity<ImportBatchResponse> confirmImport(UUID id, ImportConfirmRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(response(confirmation.confirm(current.require(), id, request.getVersion())));
    }

    @Override
    public ResponseEntity<ImportRowsPage> listImportRows(UUID id, Integer cursor, Integer limit) {
        var user = current.require();
        store.get(user.tenantId(), id, false);
        var rows = store.rows(user.tenantId(), id, cursor, limit + 1);
        var items = rows.stream().limit(limit).map(row -> new ImportRowResponse(row.recordNumber(), row.rowNumber(),
                row.customerCode(), row.customerName(), row.valid())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ImportRowsPage(items, rows.size() > limit ? items.getLast().getRecordNumber() : 0));
    }

    @Override
    public ResponseEntity<ImportErrorsPage> listImportErrors(UUID id, Integer cursor, Integer limit) {
        var user = current.require();
        store.get(user.tenantId(), id, false);
        var errors = store.errors(user.tenantId(), id, cursor, limit + 1);
        var items = errors.stream().limit(limit).map(entry -> {
            CsvError error = entry.value();
            return new ImportErrorResponse(entry.number(), error.rowNumber(), error.field(), error.inputValue(),
                    error.inputTruncated(), error.reasonCode(), error.reason(), error.correction());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ImportErrorsPage(items, errors.size() > limit ? items.getLast().getErrorNumber() : 0));
    }

    @Override
    public ResponseEntity<Resource> downloadImportErrors(UUID id) {
        var user = current.require();
        store.get(user.tenantId(), id, false);
        return csv(new InputStreamResource(new ImportErrorStream(store, user.tenantId(), id)), "import-errors.csv");
    }

    private ResponseEntity<Resource> csv(Resource resource, String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(CSV)
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"").body(resource);
    }

    ImportBatchResponse response(ImportBatch batch) {
        return new ImportBatchResponse(batch.id(), ImportBatchResponse.KindEnum.CUSTOMER,
                ImportBatchResponse.StatusEnum.fromValue(batch.status().name()), batch.version(), batch.rowCount(), batch.errorCount(),
                batch.createdAt().atOffset(ZoneOffset.UTC), batch.expiresAt().atOffset(ZoneOffset.UTC),
                batch.confirmedAt() == null ? null : batch.confirmedAt().atOffset(ZoneOffset.UTC),
                batch.status() == ImportBatch.Status.CONFIRMED ? batch.rowCount() : 0).originalBatchId(store.confirmedDuplicate(batch));
    }
}
