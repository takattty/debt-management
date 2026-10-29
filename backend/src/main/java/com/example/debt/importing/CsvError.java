package com.example.debt.importing;

public record CsvError(int rowNumber, String field, String inputValue, boolean inputTruncated,
                       String reasonCode, String reason, String correction) {
    public static final int MAX_ERROR_CODE_POINTS = 200;

    public CsvError {
        int count = inputValue.codePointCount(0, inputValue.length());
        if (count > MAX_ERROR_CODE_POINTS) {
            inputValue = inputValue.substring(0, inputValue.offsetByCodePoints(0, MAX_ERROR_CODE_POINTS));
            inputTruncated = true;
        }
    }
}
