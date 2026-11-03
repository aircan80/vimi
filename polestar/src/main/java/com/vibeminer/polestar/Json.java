package com.vibeminer.polestar;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded module-local metadata codec; upstream response bytes are preserved separately. */
final class Json {
    private Json() {}

    private static final JsonFactory FACTORY =
            JsonFactory.builder()
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(128)
                                    .maxStringLength(8 << 20)
                                    .maxNumberLength(1024)
                                    .build())
                    .build();

    public static Object parse(byte[] bytes, int maxBytes) {
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("JSON byte limit exceeded");
        }
        try (var parser = FACTORY.createParser(bytes)) {
            if (parser.nextToken() == null) {
                throw new IllegalArgumentException("Empty JSON");
            }
            var result = read(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Trailing JSON");
            }
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException("Malformed JSON", error);
        }
    }

    private static Object read(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                var map = new LinkedHashMap<String, Object>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        throw new IllegalArgumentException("Expected JSON field");
                    }
                    String key = parser.currentName();
                    if (map.containsKey(key)) {
                        throw new IllegalArgumentException("Duplicate JSON field");
                    }
                    parser.nextToken();
                    map.put(key, read(parser));
                }
                yield map;
            }
            case START_ARRAY -> {
                var list = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    list.add(read(parser));
                }
                yield list;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> {
                var number = parser.getBigIntegerValue();
                yield number.bitLength() < 64 ? number.longValue() : number;
            }
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Unsupported JSON token");
        };
    }

    public static byte[] bytes(Object value) {
        try (var output = new ByteArrayOutputStream();
                var generator = FACTORY.createGenerator(output)) {
            write(generator, value);
            generator.flush();
            return output.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void write(JsonGenerator out, Object value) throws IOException {
        if (value == null) {
            out.writeNull();
            return;
        }
        if (value instanceof String text) {
            out.writeString(text);
            return;
        }
        if (value instanceof Boolean b) {
            out.writeBoolean(b);
            return;
        }
        if (value instanceof BigInteger b) {
            out.writeNumber(b);
            return;
        }
        if (value instanceof BigDecimal b) {
            out.writeNumber(b);
            return;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            out.writeNumber(((Number) value).longValue());
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Non-finite JSON");
            }
            out.writeNumber(number);
            return;
        }
        if (value instanceof Enum<?> enumeration) {
            out.writeString(enumeration.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.writeStartObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                out.writeFieldName(key);
                write(out, entry.getValue());
            }
            out.writeEndObject();
            return;
        }
        if (value instanceof Iterable<?> values) {
            out.writeStartArray();
            for (var entry : values) {
                write(out, entry);
            }
            out.writeEndArray();
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Expected JSON string");
        }
        return text;
    }

    public static long number(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof BigInteger number) {
            return number.longValueExact();
        }
        throw new IllegalArgumentException("Expected JSON integer");
    }
}
