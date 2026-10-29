package com.example.debt.importing;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Bounded syntax codec only; header and business-field validation belong to import use cases. */
public final class CsvParser {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_DATA_ROWS = 10_000;
    public static final int MAX_CELL_CODE_POINTS = 2048;
    public static final int MAX_COLUMNS = 64;

    /** Does not close the caller's stream. Failed parses never return partially parsed rows. */
    public CsvResult parse(InputStream source) {
        return new Parser(source).parse();
    }

    private enum State { START, UNQUOTED, QUOTED, AFTER_QUOTE }

    private static final class Parser {
        private final InputStream source;
        private final List<CsvRow> rows = new ArrayList<>();
        private final List<String> cells = new ArrayList<>();
        private final StringBuilder cell = new StringBuilder();
        private State state = State.START;
        private int bytes;
        private int line = 1;
        private int rowStart = 1;
        private int cellPoints;
        private boolean previousCr;
        private boolean skipLf;
        private boolean rowStarted;

        private Parser(InputStream source) {
            this.source = new BufferedInputStream(source, 8192);
        }

        private CsvResult parse() {
            try {
                boolean first = true;
                int point;
                while ((point = readPoint()) != -1) {
                    if (point == 0xfeff) {
                        if (!first) {
                            throw failure("UNEXPECTED_BOM", "BOMが先頭以外にあります。", "BOMはファイル先頭だけにしてください。");
                        }
                    } else {
                        consume(point);
                    }
                    first = false;
                }
                if (state == State.QUOTED) {
                    throw failure("UNCLOSED_QUOTE", "引用符が閉じられていません。", "項目の末尾に引用符を追加してください。");
                }
                if (rowStarted) {
                    finishRow();
                }
                return new CsvResult(rows, List.of());
            } catch (Failure failure) {
                return new CsvResult(List.of(), List.of(failure.error));
            } catch (IOException exception) {
                return new CsvResult(List.of(), List.of(error("READ_ERROR", "ファイルを読み取れません。", "ファイルを確認して再アップロードしてください。")));
            }
        }

        private void consume(int point) throws Failure {
            if (skipLf) {
                skipLf = false;
                if (point == '\n') {
                    previousCr = false;
                    return;
                }
            }
            if (point == '\r' || point == '\n') {
                if (state == State.QUOTED) {
                    append(point);
                } else {
                    finishRow();
                    skipLf = point == '\r';
                }
                if (point == '\r' || !previousCr) {
                    line++;
                }
                previousCr = point == '\r';
                if (state != State.QUOTED) {
                    rowStart = line;
                }
                return;
            }
            previousCr = false;
            rowStarted = true;
            if (point == '"') {
                switch (state) {
                    case START -> state = State.QUOTED;
                    case QUOTED -> state = State.AFTER_QUOTE;
                    case AFTER_QUOTE -> { append('"'); state = State.QUOTED; }
                    case UNQUOTED -> throw failure("UNEXPECTED_QUOTE", "引用符の位置が不正です。", "項目全体を引用符で囲み、項目内の引用符は二重にしてください。");
                }
            } else if (point == ',' && state != State.QUOTED) {
                finishCell();
            } else if (state == State.AFTER_QUOTE) {
                throw failure("AFTER_QUOTE", "閉じ引用符の後に文字があります。", "閉じ引用符の後はカンマまたは改行にしてください。");
            } else {
                append(point);
                if (state == State.START) {
                    state = State.UNQUOTED;
                }
            }
        }

        private void append(int point) throws Failure {
            if (cellPoints == MAX_CELL_CODE_POINTS) {
                throw failure("CELL_LIMIT", "項目が長すぎます。", "1項目を2048文字以内にしてください。");
            }
            cell.appendCodePoint(point);
            cellPoints++;
        }

        private void finishCell() throws Failure {
            if (cells.size() == MAX_COLUMNS) {
                throw failure("COLUMN_LIMIT", "列数が多すぎます。", "固定CSVの列構成を確認してください。");
            }
            cells.add(cell.toString());
            cell.setLength(0);
            cellPoints = 0;
            state = State.START;
        }

        private void finishRow() throws Failure {
            if (rows.size() == MAX_DATA_ROWS + 1) {
                throw failure("ROW_LIMIT", "行数が上限を超えています。", "データ行を10000行以内に分割してください。");
            }
            finishCell();
            rows.add(new CsvRow(rowStart, cells));
            cells.clear();
            rowStarted = false;
        }

        private int readByte() throws IOException, Failure {
            int value = source.read();
            if (value != -1 && ++bytes > MAX_BYTES) {
                throw failure("SIZE_LIMIT", "ファイルが大きすぎます。", "10MiB以内のファイルに分割してください。");
            }
            return value;
        }

        // Decode incrementally so errors identify the record's physical start line without buffering the file.
        private int readPoint() throws IOException, Failure {
            int first = readByte();
            if (first < 0x80) {
                return first;
            }
            int remaining = first >= 0xc2 && first <= 0xdf ? 1
                    : first >= 0xe0 && first <= 0xef ? 2 : first >= 0xf0 && first <= 0xf4 ? 3 : -1;
            if (remaining == -1) {
                throw invalidEncoding();
            }
            int point = first & (0x7f >> (remaining + 1));
            int minimum = remaining == 1 ? 0x80 : remaining == 2 ? 0x800 : 0x10000;
            for (int index = 0; index < remaining; index++) {
                int next = readByte();
                if (next < 0x80 || next > 0xbf) {
                    throw invalidEncoding();
                }
                point = (point << 6) | (next & 0x3f);
            }
            if (point < minimum || point > 0x10ffff || point >= 0xd800 && point <= 0xdfff) {
                throw invalidEncoding();
            }
            return point;
        }

        private Failure invalidEncoding() {
            return failure("INVALID_UTF8", "UTF-8として読み取れません。", "UTF-8形式で保存し直してください。");
        }

        private Failure failure(String code, String reason, String correction) {
            return new Failure(error(code, reason, correction));
        }

        private CsvError error(String code, String reason, String correction) {
            return new CsvError(rowStart, "column_" + (cells.size() + 1), cell.toString(), false, code, reason, correction);
        }
    }

    private static final class Failure extends Exception {
        private final CsvError error;

        private Failure(CsvError error) {
            this.error = error;
        }
    }
}
