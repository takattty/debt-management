package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvErrorWriterTest {
    @Test
    void neutralizesFormulaPrefixesAfterWhitespaceInActualDecodedCells() throws IOException {
        List<String> inputs = List.of("=1+1", "+SUM(1)", "-2+3", "@SUM(1)", "  =1+1", "\u00a0+1",
                "\t=1", "\r=1", "\n=1", "safe\ttext", "  \t", "\u0000=1", "\u200b=1");
        List<CsvError> errors = new ArrayList<>();
        for (String input : inputs) {
            errors.add(new CsvError(2, "amount", input, false, "INVALID", "不正です。", "修正してください。"));
        }
        CsvResult result = report(errors);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).hasSize(inputs.size() + 1);
        for (int index = 0; index < inputs.size(); index++) {
            assertThat(result.rows().get(index + 1).cells().get(2)).isEqualTo("'" + inputs.get(index));
        }
    }

    @Test
    void quotesEveryCellAndPreservesJapaneseCommasQuotesAndTruncationIndicator() throws IOException {
        CsvError error = new CsvError(17, "+field", "架空,\"𠮷\"", true, "INVALID", "\t理由", "\n修正");
        CsvResult result = report(List.of(error));
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().getFirst().cells()).containsExactly("row_number", "field", "input_value",
                "input_truncated", "reason", "correction");
        assertThat(result.rows().get(1).cells()).containsExactly("17", "'+field", "架空,\"𠮷\"", "true", "'\t理由", "'\n修正");
    }

    private CsvResult report(List<CsvError> errors) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new CsvErrorWriter().write(errors, output);
        byte[] bytes = output.toByteArray();
        assertThat(bytes).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        return new CsvParser().parse(new ByteArrayInputStream(bytes));
    }
}
