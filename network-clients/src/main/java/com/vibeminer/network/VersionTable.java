package com.vibeminer.network;

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

/** Strict bounded version-service grammar; values are opaque strings. */
public record VersionTable(List<String> columns, List<Map<String, String>> rows, Long sequence) {
    private static final Pattern DESCRIPTOR =
            Pattern.compile("([A-Za-z][A-Za-z0-9_]*)!([A-Za-z][A-Za-z0-9_]*):([0-9]+)");
    private static final Pattern SEQUENCE = Pattern.compile("## seqn = ([0-9]{1,18})");

    public VersionTable {
        columns = List.copyOf(columns);
        rows = rows.stream().map(Map::copyOf).toList();
    }

    public static VersionTable parse(byte[] bytes) {
        if (bytes.length > (1 << 20)) {
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
            throw invalid("Invalid UTF-8");
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
        List<Map<String, String>> rows = new ArrayList<>();
        Long sequence = null;
        for (String line : text.split("\n", -1)) {
            String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (row.length() > 16384) {
                throw invalid("Line exceeds 16384 characters");
            }
            if (row.isEmpty()) {
                continue;
            }
            if (row.startsWith("#")) {
                var match = SEQUENCE.matcher(row);
                if (match.matches()) {
                    long value = Long.parseLong(match.group(1));
                    if (sequence != null && sequence != value) {
                        throw invalid("Conflicting sequences");
                    }
                    sequence = value;
                }
                continue;
            }
            String[] cells = row.split("\\|", -1);
            if (columns == null) {
                if (cells.length > 64) {
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
            if (cells.length != columns.size() || rows.size() == 10000) {
                throw invalid("Wrong cell count or too many rows");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < cells.length; i++) {
                values.put(columns.get(i), cells[i]);
            }
            rows.add(values);
        }
        if (columns == null) {
            throw invalid("No table header");
        }
        return new VersionTable(columns, rows, sequence);
    }

    public record Routes(Map<String, CdnRoute> usable, Map<String, String> unusable) {
        public Routes {
            usable = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(usable));
            unusable = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(unusable));
        }
    }

    public Routes routes() {
        if (!columns.containsAll(List.of("Name", "Path", "Hosts"))) {
            throw invalid("CDNs table needs Name, Path and Hosts");
        }
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            grouped.computeIfAbsent(row.get("Name"), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, CdnRoute> usable = new java.util.TreeMap<>();
        Map<String, String> unusable = new java.util.TreeMap<>();
        for (var group : grouped.entrySet()) {
            if (group.getValue().size() != 1) {
                unusable.put(group.getKey(), "ROUTE_AMBIGUOUS");
                continue;
            }
            var row = group.getValue().getFirst();
            try {
                usable.put(
                        group.getKey(),
                        new CdnRoute(List.of(row.get("Hosts").split(" +")), row.get("Path")));
            } catch (IllegalArgumentException e) {
                unusable.put(group.getKey(), "ROUTE_INVALID");
            }
        }
        return new Routes(usable, unusable);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
