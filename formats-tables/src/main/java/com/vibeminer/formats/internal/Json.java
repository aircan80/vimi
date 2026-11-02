package com.vibeminer.formats.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict bounded JSON and .NET-default-compatible canonical escaping for persisted identities. */
public final class Json {
    private Json() {}

    public static Object parse(byte[] bytes, int maxBytes) {
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("JSON byte bound exceeded");
        }
        try (var p =
                JsonFactory.builder()
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(32)
                                        .maxStringLength(maxBytes)
                                        .build())
                        .build()
                        .createParser(bytes)) {
            p.nextToken();
            var value = read(p);
            if (p.nextToken() != null) {
                throw new IllegalArgumentException("Trailing JSON");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    private static Object read(JsonParser p) throws IOException {
        return switch (p.currentToken()) {
            case START_ARRAY -> {
                var a = new ArrayList<Object>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    a.add(read(p));
                }
                yield a;
            }
            case START_OBJECT -> {
                var m = new LinkedHashMap<String, Object>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String k = p.currentName();
                    if (p.currentToken() != JsonToken.FIELD_NAME || m.containsKey(k)) {
                        throw new IllegalArgumentException("Repeated JSON property");
                    }
                    p.nextToken();
                    m.put(k, read(p));
                }
                yield m;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Unsupported JSON value");
        };
    }

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Object required");
        }
        var result = new LinkedHashMap<String, Object>();
        for (var e : map.entrySet()) {
            result.put((String) e.getKey(), e.getValue());
        }
        return result;
    }

    public static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Array required");
        }
        return list;
    }

    public static String string(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("String required");
        }
        return s;
    }

    public static long number(Object value) {
        if (!(value instanceof Long n)) {
            throw new IllegalArgumentException("Integer required");
        }
        return n;
    }

    public static byte[] bytes(Object value) {
        return canonical(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonical(Object value) {
        var b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        if (value == null) {
            b.append("null");
            return;
        }
        if (value instanceof String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    b.append("\\\\");
                } else if (c == '\n') {
                    b.append("\\n");
                } else if (c == '\r') {
                    b.append("\\r");
                } else if (c == '\t') {
                    b.append("\\t");
                } else if (c == '\b') {
                    b.append("\\b");
                } else if (c == '\f') {
                    b.append("\\f");
                } else if (c < ' ' || c > '~' || "\"<>&'`+".indexOf(c) >= 0) {
                    b.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) c));
                } else {
                    b.append(c);
                }
            }
            b.append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            b.append(value);
            return;
        }
        if (value instanceof Enum<?> e) {
            write(b, e.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, e.getKey().toString());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
            return;
        }
        if (value instanceof Iterable<?> list) {
            b.append('[');
            boolean first = true;
            for (var e : list) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, e);
            }
            b.append(']');
            return;
        }
        if (value.getClass().isRecord()) {
            var m = new LinkedHashMap<String, Object>();
            for (var c : value.getClass().getRecordComponents()) {
                try {
                    String name = c.getName();
                    if (value instanceof com.vibeminer.formats.db2.Db2Types.Column column
                            && name.equals("narrowing")
                            && column.narrowing() == null) {
                        continue;
                    }
                    m.put(
                            Character.toUpperCase(name.charAt(0)) + name.substring(1),
                            c.getAccessor().invoke(value));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            write(b, m);
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON type " + value.getClass());
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static <T> T convert(Object value, Class<T> target) {
        try {
            return target.cast(convertType(value, target));
        } catch (ArithmeticException | ClassCastException e) {
            throw new IllegalArgumentException("Malformed typed JSON", e);
        }
    }

    private static Object convertType(Object value, java.lang.reflect.Type target) {
        if (value == null) {
            return null;
        }
        if (target instanceof java.lang.reflect.ParameterizedType p) {
            if (p.getRawType() == List.class) {
                var list = new ArrayList<Object>();
                for (var item : array(value)) {
                    list.add(convertType(item, p.getActualTypeArguments()[0]));
                }
                return List.copyOf(list);
            }
            throw new IllegalArgumentException("Unsupported generic JSON type");
        }
        Class<?> type = (Class<?>) target;
        if (type == String.class) {
            return string(value);
        }
        if (type == long.class || type == Long.class) {
            return number(value);
        }
        if (type == int.class || type == Integer.class) {
            return Math.toIntExact(number(value));
        }
        if (type == boolean.class || type == Boolean.class) {
            return (Boolean) value;
        }
        if (type.isRecord()) {
            var map = object(value);
            var components = type.getRecordComponents();
            var args = new Object[components.length];
            var types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                var c = components[i];
                String name = c.getName();
                name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                Object field;
                if (map.containsKey(name)) {
                    field = map.get(name);
                } else if (type == com.vibeminer.formats.db2.Db2Types.Column.class
                        && (name.equals("Interpretation") || name.equals("Narrowing"))) {
                    field = null;
                } else if (type == com.vibeminer.formats.db2.Db2Types.Completion.class
                        && name.equals("TextIssueCount")) {
                    field = 0L;
                } else if (type == com.vibeminer.formats.db2.Db2Types.Section.class
                        && (name.equals("ProtectedCopyCount")
                                || name.equals("UnresolvedCopyCount"))) {
                    field = 0L;
                } else if (type == com.vibeminer.formats.db2.Db2Types.Section.class
                        && name.equals("IsDecrypted")) {
                    field = false;
                } else {
                    throw new IllegalArgumentException("Missing required JSON field " + name);
                }
                args[i] = convertType(field, c.getGenericType());
                types[i] = c.getType();
            }
            try {
                return type.getDeclaredConstructor(types).newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Malformed record JSON", e);
            }
        }
        throw new IllegalArgumentException("Unsupported JSON target " + type);
    }
}
