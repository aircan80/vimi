package com.vibeminer.eglink;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EgJson {
    private static final JsonFactory FACTORY =
            JsonFactory.builder()
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(48)
                                    .maxStringLength(1 << 20)
                                    .maxNumberLength(128)
                                    .build())
                    .build();

    private EgJson() {}

    static Object parse(byte[] bytes, int max) throws IOException {
        if (bytes.length == 0 || bytes.length > max) {
            throw new IOException("EG Link JSON exceeds byte bounds");
        }
        try (JsonParser parser = FACTORY.createParser(bytes)) {
            parser.nextToken();
            Object value = read(parser, new int[] {0});
            if (parser.nextToken() != null) {
                throw new IOException("Trailing EG Link JSON content");
            }
            return value;
        }
    }

    private static Object read(JsonParser parser, int[] nodes) throws IOException {
        if (++nodes[0] > 2_000_000) {
            throw new IOException("Too many JSON values");
        }
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            var map = new LinkedHashMap<String, Object>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("Expected JSON field");
                }
                String name = parser.currentName();
                if (map.containsKey(name)) {
                    throw new IOException("Duplicate JSON field");
                }
                parser.nextToken();
                map.put(name, read(parser, nodes));
            }
            return map;
        }
        if (token == JsonToken.START_ARRAY) {
            var list = new ArrayList<Object>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                list.add(read(parser, nodes));
            }
            return list;
        }
        if (token == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return parser.getBigIntegerValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new IOException("Unsupported JSON token");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String text(Object value) {
        if (!(value instanceof String text) || text.length() > 4096) {
            throw new IllegalArgumentException("Expected bounded text");
        }
        return text;
    }

    static long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected nonnegative integer");
        }
        try {
            long result = new java.math.BigDecimal(number.toString()).longValueExact();
            if (result < 0) {
                throw new IllegalArgumentException("Expected nonnegative integer");
            }
            return result;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected bounded integer", invalid);
        }
    }

    static byte[] bytes(Object value) throws IOException {
        var out = new ByteArrayOutputStream();
        try (JsonGenerator writer = FACTORY.createGenerator(out)) {
            write(writer, value);
        }
        return out.toByteArray();
    }

    private static void write(JsonGenerator writer, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            writer.writeStartObject();
            for (var entry : map.entrySet()) {
                writer.writeFieldName(entry.getKey().toString());
                write(writer, entry.getValue());
            }
            writer.writeEndObject();
        } else if (value instanceof List<?> list) {
            writer.writeStartArray();
            for (var item : list) {
                write(writer, item);
            }
            writer.writeEndArray();
        } else if (value instanceof String text) {
            writer.writeString(text);
        } else if (value instanceof Number number) {
            writer.writeNumber(number.toString());
        } else if (value instanceof Boolean bool) {
            writer.writeBoolean(bool);
        } else if (value == null) {
            writer.writeNull();
        } else {
            throw new IOException("Unsupported JSON value");
        }
    }
}
