package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.definitions.DbdTypes.BuildRange;
import com.vibeminer.formats.definitions.DbdTypes.BuildVersion;
import com.vibeminer.formats.definitions.DbdTypes.Column;
import com.vibeminer.formats.definitions.DbdTypes.Field;
import com.vibeminer.formats.definitions.DbdTypes.Limits;
import com.vibeminer.formats.definitions.DbdTypes.Reference;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded strict UTF-8 WoWDBDefs grammar reader; no acquisition or game data. */
public final class DbdDefinitionReader {
    private DbdDefinitionReader() {}

    private static final Pattern COLUMN =
            Pattern.compile(
                    "^(int|float|string|locstring)(?:<([A-Za-z_][A-Za-z0-9_]*)::([A-Za-z_][A-Za-z0-9_]*)>)?[ \\t]+([A-Za-z_][A-Za-z0-9_]*)(\\?)?$");
    private static final Pattern FIELD =
            Pattern.compile(
                    "^(?:\\$([^$]+)\\$)?([A-Za-z_][A-Za-z0-9_]*)(?:<(u?)([0-9]+)>)?(?:\\[([0-9]+)\\])?$");

    private record Line(String text, String comment) {}

    private record Head(List<List<Line>> groups, LinkedHashMap<String, Column> columns) {}

    public static DbdDocument parse(byte[] bytes) {
        return parse(bytes, new Limits(), Cancellation.NONE);
    }

    public static DbdDocument parse(byte[] bytes, Limits limits, Cancellation token) {
        var head = head(bytes, limits, token, false);
        var blocks = new ArrayList<Block>();
        int selectors = 0, layoutsTotal = 0;
        long cost = 0;
        for (var lines : head.groups().subList(1, head.groups().size())) {
            token.check();
            var layouts = new ArrayList<String>();
            var layoutSet = new HashSet<String>();
            var builds = new ArrayList<BuildRange>();
            var fields = new ArrayList<Field>();
            var seen = new HashSet<String>();
            boolean hasLayout = false;
            String comment = null;
            for (var line : lines) {
                token.check();
                String body = line.text();
                boolean layout = body.startsWith("LAYOUT ");
                if (layout || body.startsWith("BUILD ") || body.startsWith("COMMENT ")) {
                    if (!fields.isEmpty()) {
                        throw error("DBD_INVALID", "Selectors must precede fields");
                    }
                    if (body.startsWith("COMMENT ")) {
                        if (comment != null) {
                            throw error("DBD_INVALID", "Repeated block comment");
                        }
                        comment = body.substring(8);
                        bounded(comment.length(), limits.maxCommentCharacters());
                        continue;
                    }
                    if (layout && hasLayout) {
                        throw error("DBD_INVALID", "Repeated LAYOUT line");
                    }
                    hasLayout |= layout;
                    for (var raw : body.substring(layout ? 7 : 6).split(",", -1)) {
                        bounded(++selectors, limits.maxSelectors());
                        String value = trim(raw);
                        if (layout) {
                            if (!isLayout(value)
                                    || !layoutSet.add(value.toUpperCase(Locale.ROOT))) {
                                throw error("DBD_INVALID", "Malformed or repeated layout");
                            }
                            layouts.add(value.toUpperCase(Locale.ROOT));
                        } else {
                            var range = value.split("-", -1);
                            if (range.length > 2) {
                                throw error("DBD_INVALID", "Malformed build range");
                            }
                            var first = BuildVersion.parse(range[0]);
                            var last = range.length == 2 ? BuildVersion.parse(range[1]) : first;
                            if (first.compareTo(last) > 0) {
                                throw error("DBD_INVALID", "Reversed build range");
                            }
                            builds.add(new BuildRange(first, last));
                        }
                    }
                    continue;
                }
                var match = FIELD.matcher(body);
                if (!match.matches()) {
                    throw error("DBD_UNSUPPORTED", "Unsupported field grammar");
                }
                var column = head.columns().get(match.group(2));
                if (column == null || !seen.add(match.group(2))) {
                    throw error("DBD_INVALID", "Undefined or repeated field");
                }
                var annotations = new HashSet<String>();
                if (match.group(1) != null) {
                    for (var a : match.group(1).split(",", -1)) {
                        if (!List.of("id", "noninline", "relation").contains(a)
                                || !annotations.add(a)) {
                            throw error("DBD_UNSUPPORTED", "Unsupported or repeated annotation");
                        }
                    }
                }
                Integer bits = null;
                Boolean unsigned = null;
                if (match.group(4) != null) {
                    int width = integer(match.group(4), "DBD_UNSUPPORTED");
                    if (!column.family().equals("int") || !List.of(8, 16, 32, 64).contains(width)) {
                        throw error("DBD_UNSUPPORTED", "Unsupported scalar width");
                    }
                    bits = width;
                    unsigned = match.group(3).equals("u");
                }
                int array = match.group(5) == null ? 1 : integer(match.group(5), "DBD_INVALID");
                if (array < 1) {
                    throw error("DBD_INVALID", "Array length must be positive");
                }
                bounded(array, limits.maxArrayLength());
                String joined =
                        column.comment() == null
                                ? line.comment()
                                : line.comment() == null
                                        ? column.comment()
                                        : column.comment() + "\n" + line.comment();
                if (joined != null) {
                    bounded(joined.length(), limits.maxCommentCharacters());
                }
                fields.add(
                        new Field(
                                column,
                                bits,
                                unsigned,
                                array,
                                annotations.contains("id"),
                                annotations.contains("noninline"),
                                annotations.contains("relation"),
                                joined));
                bounded(fields.size(), limits.maxFieldsPerBlock());
            }
            if ((builds.isEmpty() && layouts.isEmpty()) || fields.isEmpty()) {
                throw error("DBD_INVALID", "Block requires selectors and fields");
            }
            layoutsTotal += layouts.size();
            bounded(layoutsTotal, limits.maxLayouts());
            long fieldCost = fields.stream().mapToLong(DbdDefinitionReader::selectionBytes).sum();
            cost += Math.max(1, layouts.size()) * fieldCost;
            if (cost > limits.maxSelectionBytes()) {
                throw error("DBD_LIMIT", "Layout selection exceeds bound");
            }
            blocks.add(new Block(layouts, builds, fields, comment));
        }
        if (blocks.isEmpty()) {
            throw error("DBD_INVALID", "Version block required");
        }
        token.check();
        return new DbdDocument(List.copyOf(head.columns().values()), blocks, cost);
    }

    public static List<Column> parseColumns(byte[] bytes, Limits limits, Cancellation token) {
        return List.copyOf(head(bytes, limits, token, true).columns().values());
    }

    private static Head head(byte[] bytes, Limits limits, Cancellation token, boolean columnsOnly) {
        token.check();
        bounded(bytes.length, limits.maxBytes());
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
            throw error("DBD_INVALID", "Strict UTF-8 required");
        }
        for (int i = 0; i < text.length(); i++) {
            if ((i & 4095) == 0) {
                token.check();
            }
            char c = text.charAt(i);
            if (c == '\ufeff'
                    || Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r'
                    || c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                throw error("DBD_INVALID", "Unsupported control character or line ending");
            }
        }
        var groups = new ArrayList<List<Line>>();
        var group = new ArrayList<Line>();
        int count = 0;
        var rawLines = text.split("\\r?\\n", -1);
        int actualLines = rawLines.length - (text.endsWith("\n") ? 1 : 0);
        for (int lineIndex = 0; lineIndex < actualLines; lineIndex++) {
            var raw = rawLines[lineIndex];
            token.check();
            bounded(++count, limits.maxLines());
            bounded(raw.length(), limits.maxLineCharacters());
            if (trim(raw).startsWith("//")) {
                continue;
            }
            int at = raw.indexOf("//");
            String body = trim(at < 0 ? raw : raw.substring(0, at));
            String comment = at < 0 ? null : trim(raw.substring(at + 2));
            if (comment != null) {
                bounded(comment.length(), limits.maxCommentCharacters());
                if (comment.isEmpty()) {
                    comment = null;
                }
            }
            if (body.isEmpty()) {
                if (!group.isEmpty()) {
                    groups.add(group);
                    group = new ArrayList<>();
                    if (columnsOnly) {
                        break;
                    }
                }
            } else {
                group.add(new Line(body, comment));
            }
        }
        if (!group.isEmpty()) {
            groups.add(group);
        }
        if (groups.isEmpty() || !groups.getFirst().getFirst().text().equals("COLUMNS")) {
            throw error("DBD_INVALID", "Initial COLUMNS section required");
        }
        if (!columnsOnly) {
            bounded(groups.size() - 1, limits.maxBlocks());
        }
        var columns = new LinkedHashMap<String, Column>();
        for (var line : groups.getFirst().subList(1, groups.getFirst().size())) {
            token.check();
            var match = COLUMN.matcher(line.text());
            if (!match.matches()) {
                throw error("DBD_UNSUPPORTED", "Unsupported column grammar");
            }
            for (int i : new int[] {2, 3, 4}) {
                if (match.group(i) != null) {
                    bounded(match.group(i).length(), 128);
                }
            }
            var column =
                    new Column(
                            match.group(4),
                            match.group(1),
                            match.group(5) == null,
                            line.comment(),
                            match.group(2) == null
                                    ? null
                                    : new Reference(match.group(2), match.group(3)));
            if (columns.putIfAbsent(column.name(), column) != null) {
                throw error("DBD_INVALID", "Repeated column");
            }
            bounded(columns.size(), limits.maxColumns());
        }
        if (columns.isEmpty()) {
            throw error("DBD_EMPTY", "Table has no described columns");
        }
        return new Head(groups, columns);
    }

    private static String trim(String value) {
        int first = 0, last = value.length();
        while (first < last && space(value.charAt(first))) {
            first++;
        }
        while (last > first && space(value.charAt(last - 1))) {
            last--;
        }
        return value.substring(first, last);
    }

    private static boolean space(char value) {
        int type = Character.getType(value);
        return value >= '\t' && value <= '\r'
                || value == '\u0085'
                || type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    public static boolean isLayout(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}");
    }

    private static int integer(String value, String code) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw error(code, "Integer exceeds bound");
        }
    }

    private static void bounded(long value, long maximum) {
        if (value > maximum) {
            throw error("DBD_LIMIT", "Definition exceeds configured bound");
        }
    }

    public static long selectionBytes(Field field) {
        var c = field.column();
        return 320
                + 6L
                        * (c.name().length()
                                + c.family().length()
                                + (c.comment() == null ? 0 : c.comment().length())
                                + (field.comment() == null ? 0 : field.comment().length())
                                + (c.reference() == null
                                        ? 0
                                        : c.reference().table().length()
                                                + c.reference().column().length()));
    }

    public static long estimateSelection(DbdDocument document) {
        try {
            long total = 0;
            for (var block : document.blocks()) {
                long fields = 0;
                for (var field : block.fields()) {
                    fields = Math.addExact(fields, selectionBytes(field));
                }
                total =
                        Math.addExact(
                                total,
                                Math.multiplyExact(
                                        Math.max(1, block.layoutHashes().size()), fields));
            }
            return total;
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    static Db2FormatException error(String code, String message) {
        return new Db2FormatException(code, message);
    }
}
