package com.example.debt.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParserTest {
    private final CsvParser parser = new CsvParser();

    @Test
    void decodesBomJapaneseSupplementaryCharactersCommasAndEscapedQuotes() {
        CsvResult result = parse("\ufeffcode,name\r\nC001,\"架空,𠮷\"\"商事\"\r\n");
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).containsExactly(new CsvRow(1, List.of("code", "name")),
                new CsvRow(2, List.of("C001", "架空,𠮷\"商事")));
    }

    @Test
    void preservesQuotedNewlinesAndPhysicalStartLinesWithoutSkippingBlankRows() {
        CsvResult result = parse("h1,h2\r\n\"one\r\ntwo\nthree\",x\r\n\nlast,\n");
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).containsExactly(new CsvRow(1, List.of("h1", "h2")),
                new CsvRow(2, List.of("one\r\ntwo\nthree", "x")),
                new CsvRow(5, List.of("")), new CsvRow(6, List.of("last", "")));
        assertThat(parse("").rows()).isEmpty();
        assertThat(parse("\ufeff").rows()).isEmpty();
        assertThat(parse("\n").rows()).containsExactly(new CsvRow(1, List.of("")));
        assertThat(parse("a\r\rb").rows()).extracting(CsvRow::physicalStartLine).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsSyntaxAndInteriorBomWithoutReturningPreviouslyParsedRows() {
        assertFailure("header\na\"b", "UNEXPECTED_QUOTE", 2);
        assertFailure("header\n\"abc\"x", "AFTER_QUOTE", 2);
        assertFailure("header\n\"abc", "UNCLOSED_QUOTE", 2);
        assertFailure("header\nx\ufeff", "UNEXPECTED_BOM", 2);
        assertFailure("\ufeff\ufeffheader", "UNEXPECTED_BOM", 1);
        assertFailure("\"abc\nx", "UNCLOSED_QUOTE", 1);
    }

    @Test
    void decodesUtf8BoundaryCodePointsWithoutNormalization() {
        String boundary = "\u0080\u07ff\u0800\ud7ff\ue000" + new String(Character.toChars(0x10000))
                + new String(Character.toChars(0x10ffff));
        assertThat(parse(boundary).rows().getFirst().cells()).containsExactly(boundary);
    }

    @Test
    void refusesMalformedOverlongSurrogateAndOutOfRangeUtf8() {
        List<byte[]> invalid = List.of(new byte[] {(byte) 0xc0, (byte) 0xaf},
                new byte[] {(byte) 0xe0, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xe3}, new byte[] {(byte) 0x80});
        for (byte[] bytes : invalid) {
            byte[] prefix = "h\n".getBytes(StandardCharsets.UTF_8);
            byte[] file = new byte[prefix.length + bytes.length];
            System.arraycopy(prefix, 0, file, 0, prefix.length);
            System.arraycopy(bytes, 0, file, prefix.length, bytes.length);
            CsvResult result = parser.parse(new ByteArrayInputStream(file));
            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).singleElement().satisfies(error -> {
                assertThat(error.reasonCode()).isEqualTo("INVALID_UTF8");
                assertThat(error.rowNumber()).isEqualTo(2);
                assertThat(error.correction()).contains("UTF-8");
            });
        }
    }

    @Test
    void boundsCellsByCodePointsAndTruncatesErrorValuesWithoutSplittingSurrogates() {
        assertThat(parse("𠮷".repeat(CsvParser.MAX_CELL_CODE_POINTS)).errors()).isEmpty();
        CsvResult result = parse("h\n" + "𠮷".repeat(CsvParser.MAX_CELL_CODE_POINTS + 1));
        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.reasonCode()).isEqualTo("CELL_LIMIT");
            assertThat(error.rowNumber()).isEqualTo(2);
            assertThat(error.field()).isEqualTo("column_1");
            assertThat(error.inputTruncated()).isTrue();
            assertThat(error.inputValue()).isEqualTo("𠮷".repeat(CsvError.MAX_ERROR_CODE_POINTS));
        });
    }

    @Test
    void limitsRowsAndEmptyColumnsBeforeTheyCanGrowWithoutBound() {
        assertThat(parse("h\n" + "x\n".repeat(CsvParser.MAX_DATA_ROWS)).rows())
                .hasSize(CsvParser.MAX_DATA_ROWS + 1);
        assertFailure("h\n" + "x\n".repeat(CsvParser.MAX_DATA_ROWS + 1), "ROW_LIMIT", 10002);
        assertThat(parse(",".repeat(CsvParser.MAX_COLUMNS - 1)).errors()).isEmpty();
        assertFailure(",".repeat(CsvParser.MAX_COLUMNS), "COLUMN_LIMIT", 1);
    }

    @Test
    void stopsReadingOversizedStreamsAndAcceptsTheExactByteLimit() {
        CountingFile oversized = new CountingFile(CsvParser.MAX_BYTES + 1);
        CsvResult rejected = parser.parse(oversized);
        assertThat(rejected.rows()).isEmpty();
        assertThat(rejected.errors()).singleElement().extracting(CsvError::reasonCode).isEqualTo("SIZE_LIMIT");
        assertThat(oversized.readCount).isLessThanOrEqualTo(CsvParser.MAX_BYTES + 8192);
        CsvResult accepted = parser.parse(new CountingFile(CsvParser.MAX_BYTES));
        assertThat(accepted.errors()).isEmpty();
        assertThat(accepted.rows()).hasSizeLessThanOrEqualTo(CsvParser.MAX_DATA_ROWS + 1);
    }

    @Test
    void returnsReadFailuresAsDomainErrorsAndDoesNotCloseCallerStream() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Sensitive implementation detail");
            }
        };
        CsvResult result = parser.parse(broken);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.reasonCode()).isEqualTo("READ_ERROR");
            assertThat(error.reason()).doesNotContain("Sensitive");
        });
        var source = new ByteArrayInputStream("h".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                throw new AssertionError("Caller owns stream lifecycle");
            }
        };
        assertThat(parser.parse(source).errors()).isEmpty();
    }

    private CsvResult parse(String text) {
        return parser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertFailure(String text, String code, int line) {
        CsvResult result = parse(text);
        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.reasonCode()).isEqualTo(code);
            assertThat(error.rowNumber()).isEqualTo(line);
            assertThat(error.reason()).isNotBlank();
            assertThat(error.correction()).isNotBlank();
        });
    }

    private static final class CountingFile extends InputStream {
        private final int length;
        private int readCount;

        private CountingFile(int length) {
            this.length = length;
        }

        @Override
        public int read() {
            if (readCount == length) {
                return -1;
            }
            // A bounded 2048-character field followed by LF: no other limit masks the byte limit.
            return readCount++ % (CsvParser.MAX_CELL_CODE_POINTS + 1) == CsvParser.MAX_CELL_CODE_POINTS ? '\n' : 'a';
        }
    }
}
