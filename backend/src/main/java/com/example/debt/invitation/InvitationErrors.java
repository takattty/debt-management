package com.example.debt.invitation;

import com.example.debt.generated.model.ApiError;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InvitationErrors {
    @ExceptionHandler(InvitationUnavailable.class)
    ResponseEntity<ApiError> unavailable(InvitationUnavailable exception) {
        return ResponseEntity.status(409).body(new ApiError("INVITATION_UNAVAILABLE", "招待を処理できません。管理者へご確認ください。", List.of()));
    }
}
