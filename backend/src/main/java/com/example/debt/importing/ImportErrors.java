package com.example.debt.importing;

import com.example.debt.generated.model.ApiError;
import com.example.debt.generated.model.FieldError;
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
    @ExceptionHandler(ImportRejected.class)
    ResponseEntity<ApiError> rejected(ImportRejected exception) {
        String message = switch (exception.code()) {
            case "IMPORT_EXPIRED" -> "プレビューの有効期限が切れました。CSVを再プレビューしてください。";
            case "IMPORT_INVALID" -> "エラーがあるため1件も取り込んでいません。CSVを修正してください。";
            case "VERSION_CONFLICT" -> "取込状態が変更されています。再読み込みしてください。";
            case "IMPORT_ALREADY_CONFIRMED" -> "同じ内容のCSVは取込済みです。元の取込結果を確認してください。";
            default -> "プレビュー後に登録内容が競合しました。新しいプレビューを確認してください。";
        };
        var errors = exception.originalBatchId() == null ? List.<FieldError>of()
                : List.of(new FieldError("batchId", exception.originalBatchId().toString()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(exception.code(), message, errors));
    }

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
