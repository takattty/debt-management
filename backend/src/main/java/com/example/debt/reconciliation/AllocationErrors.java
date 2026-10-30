package com.example.debt.reconciliation;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AllocationErrors {
    @ExceptionHandler(AllocationCommands.Missing.class)
    ResponseEntity<ApiError> missing(AllocationCommands.Missing missing) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "入金、請求または消込が見つかりません。", List.of()));
    }
    @ExceptionHandler(AllocationCommands.Conflict.class)
    ResponseEntity<ApiError> conflict(AllocationCommands.Conflict conflict) {
        return ResponseEntity.status(409).body(new ApiError(conflict.getMessage(), "残高・版・操作を確認して最新の内容を再読み込みしてください。", List.of()));
    }
}
