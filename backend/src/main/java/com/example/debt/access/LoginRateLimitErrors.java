package com.example.debt.access;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LoginRateLimitErrors {
    @ExceptionHandler(LoginRateLimited.class)
    ResponseEntity<ApiError> limited(LoginRateLimited exception) {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(new ApiError("RATE_LIMITED", "試行回数の上限に達しました。しばらく待ってから再試行してください。", List.of()));
    }
}
