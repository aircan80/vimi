package com.vibeminer.formats.gametables;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded strict numeric GameTables text reader; retains exact source tokens for provenance. */
public final class GameTableText {
    private final List<String> columns;
    private final Map<Long, List<String>> rows;

    private GameTableText(List<String> columns, Map<Long, List<String>> rows) {
        this.columns = List.copyOf(columns);
        this.rows = Map.copyOf(rows);
    }

    public static GameTableText parse(byte[] bytes) {
        if (bytes.length < 1 || bytes.length > 8 << 20) {
            throw new IllegalArgumentException("GameTable byte bound exceeded");
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
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid GameTable UTF-8", invalid);
        }
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        List<String> header = null;
        var rows = new LinkedHashMap<Long, List<String>>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            var cells = java.util.Arrays.stream(line.strip().split("\\s+", -1)).toList();
            if (header == null) {
                if (cells.size() < 2
                        || cells.size() > 128
                        || !List.of("level", "id", "index", "row")
                                .contains(cells.getFirst().toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Unsupported GameTable header");
                }
                if (cells.stream().distinct().count() != cells.size()) {
                    throw new IllegalArgumentException("Duplicate GameTable columns");
                }
                header = cells;
                continue;
            }
            if (cells.size() != header.size() || rows.size() >= 100000) {
                throw new IllegalArgumentException("Invalid GameTable row shape/bound");
            }
            long id;
            try {
                id = Long.parseLong(cells.getFirst());
                for (int i = 1; i < cells.size(); i++) {
                    if (!Float.isFinite(Float.parseFloat(cells.get(i)))) {
                        throw new IllegalArgumentException("Non-finite GameTable value");
                    }
                }
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid GameTable numeric value", invalid);
            }
            if (id < 0 || rows.putIfAbsent(id, cells) != null) {
                throw new IllegalArgumentException("Invalid/duplicate GameTable row ID");
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("Missing GameTable header");
        }
        return new GameTableText(header, rows);
    }

    public record Cell(String column, int index, long row, String text, float value) {}

    public Cell cell(long row, String... names) {
        var values = rows.get(row);
        if (values == null) {
            return null;
        }
        for (String name : names) {
            int index = columns.indexOf(name);
            if (index > 0) {
                return new Cell(
                        name, index, row, values.get(index), Float.parseFloat(values.get(index)));
            }
        }
        return null;
    }
}
