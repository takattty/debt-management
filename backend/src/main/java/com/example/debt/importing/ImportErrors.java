package com.example.debt.importing;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ImportErrors {
    @ExceptionHandler(ImportNotFound.class)
    ResponseEntity<ApiError> notFound(ImportNotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "取込が見つかりません。", List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("IMPORT_SIZE_LIMIT", "CSVは10MiB以内に分割してください。", List.of()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> missingPart(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "CSVファイルを1つ選択してください。", List.of()));
    }
}
