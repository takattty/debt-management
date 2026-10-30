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
