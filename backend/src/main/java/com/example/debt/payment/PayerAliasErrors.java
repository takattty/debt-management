package com.example.debt.payment;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PayerAliasErrors {
    @ExceptionHandler(PayerAliases.Missing.class)
    ResponseEntity<ApiError> missing(PayerAliases.Missing missing) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("NOT_FOUND", "名義対応または顧客が見つかりません。", List.of()));
    }

    @ExceptionHandler(PayerAliases.Conflict.class)
    ResponseEntity<ApiError> conflict(PayerAliases.Conflict conflict) {
        String message = conflict.getMessage().equals("PAYER_ALIAS_EXISTS") ? "同じ名義と顧客の有効な対応が登録済みです。"
                : "名義対応が変更されています。最新の内容を再読み込みしてください。";
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(new ApiError(conflict.getMessage(), message, List.of()));
    }
}
