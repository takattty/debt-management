package com.example.debt.collection;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice(assignableTypes = CollectionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CollectionErrors {
    @ExceptionHandler(CollectionActions.Conflict.class)
    ResponseEntity<ApiError> conflict(CollectionActions.Conflict conflict) {
        String message = conflict.getMessage().equals("COLLECTION_NOT_ELIGIBLE") ? "期日超過の未回収がないため督促文面の対象外です。"
                : "対応状況が変更されています。最新の内容を確認してください。";
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(new ApiError(conflict.getMessage(), message, List.of()));
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingDate(MissingServletRequestParameterException missing) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("INVALID_REQUEST", "基準日をYYYY-MM-DDで指定してください。", List.of()));
    }
    @ExceptionHandler(CollectionQueries.Missing.class)
    ResponseEntity<ApiError> missing(CollectionQueries.Missing missing) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("NOT_FOUND", "請求または対応履歴が見つかりません。", List.of()));
    }
}
