package com.vibeminer.files.names;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.vibeminer.network.SourceUrls;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ListfileRelease(String tag, Instant publishedAt, String sha256, long size) {
    int parts() {
        return (int) ((size + SourceUrls.PART_BYTES - 1) / SourceUrls.PART_BYTES);
    }

    int partLength(int index) {
        if (index < 0 || index >= parts()) {
            throw new IllegalArgumentException("Invalid part");
        }
        return (int) Math.min(SourceUrls.PART_BYTES, size - (long) index * SourceUrls.PART_BYTES);
    }

    static ListfileRelease parse(byte[] bytes, Instant at) {
        if (bytes.length > 1 << 20) {
            throw fail("LISTFILE_INVALID", "Release JSON exceeds1MiB", at);
        }
        try (JsonParser p = new JsonFactory().createParser(bytes)) {
            p.nextToken();
            Object decoded = value(p, 0);
            if (p.nextToken() != null || !(decoded instanceof Map<?, ?> root)) {
                throw fail("LISTFILE_INVALID", "Invalid release JSON", at);
            }
            Object tagValue = root.get("tag_name"), date = root.get("published_at");
            if (!(tagValue instanceof String tag)
                    || !tag.matches("[a-z0-9_-]{1,64}")
                    || !(date instanceof String timestamp)
                    || !(root.get("assets") instanceof List<?> assets)) {
                throw fail("LISTFILE_INVALID", "Invalid release fields", at);
            }
            Instant published = OffsetDateTime.parse(timestamp).toInstant();
            for (Object item : assets) {
                if (!(item instanceof Map<?, ?> asset)
                        || !SourceUrls.ASSET_NAME.equals(asset.get("name"))) {
                    continue;
                }
                if (!(asset.get("size") instanceof Long size) || size < 1) {
                    throw fail("LISTFILE_INVALID", "Invalid asset size", at);
                }
                if (size > SourceUrls.MAX_ASSET_BYTES) {
                    throw fail("LISTFILE_TOO_LARGE", "Asset exceeds512MiB", at);
                }
                if (!(asset.get("digest") instanceof String digest)
                        || !digest.matches("sha256:[0-9a-f]{64}")
                        || !SourceUrls.asset(tag)
                                .toString()
                                .equals(asset.get("browser_download_url"))) {
                    throw fail("LISTFILE_INVALID", "Asset digest or URL refused", at);
                }
                return new ListfileRelease(tag, published, digest.substring(7), size);
            }
            throw fail("LISTFILE_NOT_FOUND", "Release has no community names asset", at);
        } catch (IOException | java.time.DateTimeException e) {
            throw fail("LISTFILE_INVALID", "Invalid release JSON", at);
        }
    }

    private static Object value(JsonParser p, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("JSON depth");
        }
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            Map<String, Object> map = new LinkedHashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("Expected field");
                }
                String key = p.currentName();
                p.nextToken();
                map.put(key, value(p, depth + 1));
            }
            return map;
        }
        if (t == JsonToken.START_ARRAY) {
            List<Object> list = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                list.add(value(p, depth + 1));
            }
            return list;
        }
        if (t == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        if (t == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        if (t == JsonToken.VALUE_NULL
                || t == JsonToken.VALUE_TRUE
                || t == JsonToken.VALUE_FALSE
                || t == JsonToken.VALUE_NUMBER_FLOAT) {
            return null;
        }
        throw new IOException("Unexpected JSON token");
    }

    static ListfileFetchException fail(String code, String message, Instant at) {
        return new ListfileFetchException(code, message, at);
    }
}
