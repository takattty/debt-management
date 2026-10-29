package com.example.debt.customer;

import com.example.debt.generated.model.ApiError;
import com.example.debt.access.LoginRejected;
import com.example.debt.generated.model.FieldError;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.validation.method.ParameterErrors;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidFields(MethodArgumentNotValidException exception) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "入力内容を確認してください。", fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidMethodFields(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "処理中に問題が発生しました。");
        }
        List<FieldError> fields = exception.getParameterValidationResults().stream()
                .filter(ParameterErrors.class::isInstance).map(ParameterErrors.class::cast)
                .flatMap(result -> result.getFieldErrors().stream())
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "入力内容を確認してください。", fields));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "入力形式を確認してください。");
    }

    @ExceptionHandler(LoginRejected.class)
    ResponseEntity<ApiError> authenticationFailed(LoginRejected exception) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "メールアドレスまたはパスワードを確認してください。");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException exception) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_CUSTOMER_CODE", "顧客コードは登録済みです。別のコードを入力してください。");
    }

    @ExceptionHandler(CustomerConflict.class)
    ResponseEntity<ApiError> conflict(CustomerConflict exception) {
        return error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "他の担当者が更新しました。再読み込みしてください。");
    }

    @ExceptionHandler(CustomerNotFound.class)
    ResponseEntity<ApiError> notFound(CustomerNotFound exception) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "顧客が見つかりません。");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, List.of()));
    }
}
