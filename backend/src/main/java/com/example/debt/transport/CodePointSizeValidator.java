package com.example.debt.transport;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {
    private int minimum;
    private int maximum;

    @Override
    public void initialize(CodePointSize constraint) {
        minimum = constraint.min();
        maximum = constraint.max();
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid code point length bounds");
        }
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.codePointCount(0, value.length());
        return length >= minimum && length <= maximum;
    }
}
