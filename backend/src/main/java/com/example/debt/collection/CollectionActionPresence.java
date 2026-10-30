package com.example.debt.collection;

import com.example.debt.generated.model.CollectionActionRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

/** Required-but-nullable member presence is not represented by the generated setter DTO. */
@ControllerAdvice(assignableTypes = CollectionController.class)
public class CollectionActionPresence extends RequestBodyAdviceAdapter {
    private final JsonMapper json;
    public CollectionActionPresence(JsonMapper json) { this.json = json; }
    @Override
    public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getParameterType() == CollectionActionRequest.class;
    }
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        try {
            var tree = json.readTree(bytes);
            if (tree == null || !tree.isObject() || !tree.has("nextActionDate")) {
                throw new HttpMessageNotReadableException("nextActionDate must be present; explicit null clears the plan", input);
            }
        } catch (JacksonException invalid) {
            throw new HttpMessageNotReadableException("Expected a JSON action object", invalid, input);
        }
        return new HttpInputMessage() {
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        };
    }
}
