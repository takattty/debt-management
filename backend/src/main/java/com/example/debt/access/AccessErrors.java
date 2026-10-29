package com.example.debt.access;

import com.example.debt.generated.model.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AccessErrors {
    private final JsonMapper json;

    public AccessErrors(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(new ApiError(code, message, List.of())));
    }
}
