package com.vibeminer.builds;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Layer 3's independent bounded version-table grammar; no layer 1 parser calls. */
public record VersionsTable(List<String> columns, List<Row> rows, String sequence) {
    public static final int MAX_BYTES = 1 << 20,
            MAX_COLUMNS = 64,
            MAX_ROWS = 10000,
            MAX_LINE_CHARS = 16384;
    private static final Pattern DESCRIPTOR =
            Pattern.compile("([A-Za-z][A-Za-z0-9_]*)!([A-Za-z][A-Za-z0-9_]*):([0-9]+)");
    private static final Pattern SEQUENCE = Pattern.compile("## seqn = ([0-9]+)");

    public record Row(int ordinal, Map<String, String> fields) {
        public Row {
            fields = Map.copyOf(fields);
        }
    }

    public VersionsTable {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public static VersionsTable parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw invalid("Table exceeds 1 MiB");
        }
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException e) {
            throw invalid("Table is not UTF-8");
        }
        if (text.indexOf(0) >= 0) {
            throw invalid("NUL in table");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                throw invalid("Lone CR");
            }
        }
        List<String> columns = null;
        List<Row> rows = new ArrayList<>();
        String sequence = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.length() > MAX_LINE_CHARS) {
                throw invalid("Line is too long");
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                var match = SEQUENCE.matcher(line);
                if (match.matches()) {
                    String value = match.group(1);
                    if (sequence != null && !sequence.equals(value)) {
                        throw invalid("Conflicting sequence numbers");
                    }
                    sequence = value;
                }
                continue;
            }
            String[] cells = line.split("\\|", -1);
            if (columns == null) {
                if (cells.length > MAX_COLUMNS) {
                    throw invalid("Too many columns");
                }
                columns = new ArrayList<>();
                for (String cell : cells) {
                    var match = DESCRIPTOR.matcher(cell);
                    if (!match.matches()) {
                        throw invalid("Invalid column descriptor");
                    }
                    columns.add(match.group(1));
                }
                if (new HashSet<>(columns).size() != columns.size()) {
                    throw invalid("Duplicate columns");
                }
                continue;
            }
            if (cells.length != columns.size() || rows.size() == MAX_ROWS) {
                throw invalid("Wrong cell count or too many rows");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < cells.length; i++) {
                fields.put(columns.get(i), cells[i]);
            }
            rows.add(new Row(rows.size(), fields));
        }
        if (columns == null) {
            throw invalid("No table header");
        }
        return new VersionsTable(columns, rows, sequence);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
