package com.vibeminer.concepts.core;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded literal queries and canonical .NET-web-compatible cursor framing shared by concepts. */
public final class ConceptQueries {
    public static final int MAX_QUERY_SCALARS = 128,
            MAX_QUERY_UTF8_BYTES = 512,
            MAX_QUERY_LENGTH = 256,
            MAX_CURSOR_LENGTH = 2048;

    private ConceptQueries() {}

    public record Cursor(String b, long a, String i, String o) {}

    public static boolean isValidQuery(String value) {
        if (value == null || value.length() > MAX_QUERY_LENGTH) {
            return false;
        }
        int scalars = 0, bytes = 0;
        for (int i = 0; i < value.length(); ) {
            char ch = value.charAt(i);
            int code;
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                code = Character.toCodePoint(ch, value.charAt(i + 1));
                i += 2;
            } else {
                if (Character.isLowSurrogate(ch)) {
                    return false;
                }
                code = ch;
                i++;
            }
            if (code == 0 || ++scalars > MAX_QUERY_SCALARS) {
                return false;
            }
            bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
            if (bytes > MAX_QUERY_UTF8_BYTES) {
                return false;
            }
        }
        return true;
    }

    public static Long tryParseId(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,9})")) {
            return null;
        }
        long id = Long.parseLong(value);
        return id > 0xffff_ffffL ? null : id;
    }

    public static boolean wellFormedCursor(String value) {
        if (value == null
                || value.length() < 1
                || value.length() > MAX_CURSOR_LENGTH
                || !value.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(Base64.getUrlDecoder().decode(value))
                    .equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean containsOrdinalIgnoreCase(String text, String query) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(query);
        for (int start = 0; start <= text.length() - query.length(); start++) {
            int a = start, b = 0;
            boolean match = true;
            while (b < query.length()) {
                int x = text.codePointAt(a), y = query.codePointAt(b);
                if (x != y && (upper(x) != upper(y))) {
                    match = false;
                    break;
                }
                int xn = Character.charCount(x), yn = Character.charCount(y);
                if (xn != yn) {
                    match = false;
                    break;
                }
                a += xn;
                b += yn;
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** Stable literal-search key using the same simple Unicode case policy as contains. */
    public static String searchKey(String value) {
        var result = new StringBuilder();
        value.codePoints().forEach(code -> result.appendCodePoint(upper(code)));
        return result.toString();
    }

    private static int upper(int code) {
        return code == 0x131 || code == 0x17f ? code : Character.toUpperCase(code);
    }

    public static String binding(
            BuildRef build,
            String policy,
            String order,
            String locale,
            String query,
            Long exactId,
            int limit) {
        Objects.requireNonNull(build);
        String text =
                String.join(
                        "\n",
                        policy,
                        order,
                        build.product(),
                        build.buildConfig(),
                        build.cdnConfig(),
                        build.region() == null ? "" : build.region(),
                        locale,
                        query,
                        exactId == null ? "" : Long.toString(exactId),
                        Integer.toString(limit));
        return Json.sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String encode(Cursor cursor) {
        Objects.requireNonNull(cursor);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("b", cursor.b);
        fields.put("a", cursor.a);
        fields.put("i", cursor.i);
        fields.put("o", cursor.o);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(fields));
    }

    public static Cursor decode(String value, String binding) {
        Objects.requireNonNull(value);
        if (!wellFormedCursor(value)) {
            return null;
        }
        try {
            Map<String, Object> fields =
                    Json.object(Json.parse(Base64.getUrlDecoder().decode(value), 2048));
            Object b = fields.get("b"),
                    a = fields.get("a"),
                    i = fields.get("i"),
                    o = fields.get("o");
            if (!(b instanceof String)
                    || !(a instanceof Long)
                    || i != null && !(i instanceof String)
                    || o != null && !(o instanceof String)) {
                return null;
            }
            Cursor cursor = new Cursor((String) b, (Long) a, (String) i, (String) o);
            return cursor.a < 0
                            || !Objects.equals(cursor.b, binding)
                            || !encode(cursor).equals(value)
                    ? null
                    : cursor;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
