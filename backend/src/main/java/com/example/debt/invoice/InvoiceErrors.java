package com.example.debt.invoice;

import com.example.debt.generated.model.ApiError;
import com.example.debt.generated.model.FieldError;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvoiceErrors {
    @ExceptionHandler(InvoiceNotFound.class)
    ResponseEntity<ApiError> notFound(InvoiceNotFound missing) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(new ApiError("NOT_FOUND", "請求が見つかりません。", List.of()));
    }

    @ExceptionHandler(InvoiceConflict.class)
    ResponseEntity<ApiError> conflict(InvoiceConflict conflict) {
        String message = switch (conflict.code()) {
            case "VERSION_CONFLICT" -> "請求が変更されています。最新の内容を再読み込みしてください。";
            case "INVOICE_ALLOCATED" -> "有効な消込があります。先に対象の消込を取り消してください。";
            default -> "取消済みの請求は訂正・再取消できません。履歴を確認してください。";
        };
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(new ApiError(conflict.code(), message, List.of()));
    }

    @ExceptionHandler(InvoiceValidationException.class)
    ResponseEntity<ApiError> invalid(InvoiceValidationException invalid) {
        String field = switch (invalid.field()) {
            case "issue_date" -> "issueDate";
            case "due_date" -> "dueDate";
            case "customer_code" -> "customerId";
            default -> invalid.field();
        };
        String message = switch (invalid.reasonCode()) {
            case "DUE_BEFORE_ISSUE" -> "支払期日を請求日以降にしてください。";
            case "INVALID_REASON" -> "訂正・取消の理由を1〜1000文字で入力してください。";
            case "INVALID_AMOUNT" -> "請求額を税込の正の円整数で入力してください。";
            case "CUSTOMER_NOT_FOUND", "CUSTOMER_REQUIRED" -> "所属企業に登録済みの顧客を選択してください。";
            default -> "入力形式・値の範囲を確認してください。";
        };
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new ApiError("INVALID_REQUEST", "請求内容と理由を確認してください。",
                List.of(new FieldError(field, message))));
    }
}
