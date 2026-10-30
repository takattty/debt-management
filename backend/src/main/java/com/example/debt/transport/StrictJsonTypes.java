package com.example.debt.transport;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class StrictJsonTypes {
    @Bean
    JsonMapperBuilderCustomizer schemaTypes() {
        return builder -> builder
                .addModule(new SimpleModule("strict-iso-date").addDeserializer(LocalDate.class, new IsoDateDeserializer()))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, coercion -> coercion
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    private static final class IsoDateDeserializer extends ValueDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(LocalDate.class, "Expected an ISO date string in yyyy-MM-dd format");
            }
            String value = parser.getString();
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return context.reportInputMismatch(LocalDate.class, "Expected an ISO date string in yyyy-MM-dd format");
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException invalidDate) {
                return context.reportInputMismatch(LocalDate.class, "Expected a valid ISO date");
            }
        }
    }
}
