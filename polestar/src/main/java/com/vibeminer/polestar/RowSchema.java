package com.vibeminer.polestar;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reviewed subset used by Polestar schema version1; new schema features fail closed. */
final class RowSchema {
    private static final Set<String> KEYS =
            Set.of(
                    "$schema",
                    "$id",
                    "title",
                    "description",
                    "type",
                    "additionalProperties",
                    "required",
                    "properties",
                    "enum",
                    "minimum",
                    "minLength");
    private final Map<String, Object> schema;

    RowSchema(byte[] bytes, String table) {
        schema = Json.object(Json.parse(bytes, 64 << 10));
        if (!"schema/".concat(table).concat(".schema.json").equals(schema.get("$id"))
                || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Unsupported table schema");
        }
        checkSchema(schema, 0);
    }

    private static void checkSchema(Map<String, Object> schema, int depth) {
        if (depth > 16 || !KEYS.containsAll(schema.keySet())) {
            throw new IllegalArgumentException("Unsupported JSON Schema feature");
        }
        if (!Set.of("object", "string", "integer", "number", "boolean")
                .contains(Json.string(schema.get("type")))) {
            throw new IllegalArgumentException("Unsupported field type");
        }
        if (schema.containsKey("properties")) {
            for (var field : Json.object(schema.get("properties")).values()) {
                checkSchema(Json.object(field), depth + 1);
            }
        }
    }

    void validate(Map<String, Object> row) {
        validate(schema, row, 0);
    }

    private static void validate(Map<String, Object> schema, Object value, int depth) {
        if (depth > 16 || value == null) {
            throw new IllegalArgumentException("Invalid row value");
        }
        String type = Json.string(schema.get("type"));
        boolean valid =
                switch (type) {
                    case "object" -> value instanceof Map<?, ?>;
                    case "string" -> value instanceof String;
                    case "integer" ->
                            value instanceof Long
                                    || value instanceof Integer
                                    || value instanceof java.math.BigInteger;
                    case "number" -> value instanceof Number;
                    case "boolean" -> value instanceof Boolean;
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException("Row field type differs from published schema");
        }
        if (schema.containsKey("enum") && !Json.array(schema.get("enum")).contains(value)) {
            throw new IllegalArgumentException("Invalid enum value");
        }
        if (schema.containsKey("minimum")
                && new BigDecimal(value.toString())
                                .compareTo(new BigDecimal(schema.get("minimum").toString()))
                        < 0) {
            throw new IllegalArgumentException("Value below schema minimum");
        }
        if (schema.containsKey("minLength")
                && ((String) value).length() < Json.number(schema.get("minLength"))) {
            throw new IllegalArgumentException("String below schema length");
        }
        if ("object".equals(type)) {
            var row = Json.object(value);
            for (var required : Json.array(schema.getOrDefault("required", List.of()))) {
                if (!row.containsKey(Json.string(required))) {
                    throw new IllegalArgumentException("Missing required field");
                }
            }
            var fields = Json.object(schema.getOrDefault("properties", Map.of()));
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))
                    && !fields.keySet().containsAll(row.keySet())) {
                throw new IllegalArgumentException("Unknown row field");
            }
            for (var entry : row.entrySet()) {
                if (fields.containsKey(entry.getKey())) {
                    validate(Json.object(fields.get(entry.getKey())), entry.getValue(), depth + 1);
                }
            }
        }
    }
}
