package com.vibeminer.concepts.locations;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class LocationJson {
    private static final JsonFactory FACTORY =
            JsonFactory.builder()
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(24)
                                    .maxStringLength(1 << 20)
                                    .maxNumberLength(64)
                                    .build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();

    private LocationJson() {}

    static Map<String, Object> parse(String json) throws IOException {
        if (json == null || json.length() > 2 << 20) {
            throw new IOException("Source record exceeds supported bound");
        }
        try (var parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected source object");
            }
            var result = object(parser, new int[1]);
            if (parser.nextToken() != null) {
                throw new IOException("Trailing source data");
            }
            return result;
        }
    }

    private static Map<String, Object> object(JsonParser parser, int[] count) throws IOException {
        var result = new LinkedHashMap<String, Object>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw new IOException("Invalid source object");
            }
            String field = parser.currentName();
            parser.nextToken();
            result.put(field, value(parser, count));
        }
        return result;
    }

    private static Object value(JsonParser parser, int[] count) throws IOException {
        if (++count[0] > 100_000) {
            throw new IOException("Source record exceeds value bound");
        }
        if (parser.currentToken() == null) {
            throw new IOException("Truncated source value");
        }
        return switch (parser.currentToken()) {
            case START_OBJECT -> object(parser, count);
            case START_ARRAY -> {
                var values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    values.add(value(parser, count));
                }
                yield values;
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getBigIntegerValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("Invalid source value");
        };
    }
}
