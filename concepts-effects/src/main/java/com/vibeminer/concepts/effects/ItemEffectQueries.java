package com.vibeminer.concepts.effects;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Shared validation and canonical web-JSON cursor framing; no row lookup or authority here. */
public final class ItemEffectQueries {
    public static final String POLICY = "item-effects-link-row-order-v1";
    public static final int MAX_LIMIT = 16,
            MAX_NAME_UNITS = 4096,
            MAX_SOURCE_NAME_UNITS = 65536,
            MAX_CURSOR_LENGTH = 2048;

    private ItemEffectQueries() {}

    public record Cursor(String b, long a, String i, String l) {}

    public static void validateLocale(String locale) {
        if (com.vibeminer.formats.definitions.WowLocale.tryParse(locale) == null) {
            throw new IllegalArgumentException("An exact ROOT locale code is required");
        }
    }

    public static void validate(EffectsTypes.Query query) {
        Objects.requireNonNull(query);
        if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
            throw new IllegalArgumentException("Effects page limit must be1..16");
        }
        if (query.after() != null && !isWellFormedCursor(query.after())) {
            throw new IllegalArgumentException("The cursor is malformed.");
        }
    }

    public static boolean isWellFormedCursor(String value) {
        return com.vibeminer.concepts.core.ConceptQueries.wellFormedCursor(value);
    }

    public static String binding(BuildRef build, String locale, long itemId, int limit) {
        Objects.requireNonNull(build);
        String text =
                String.join(
                        "\n",
                        POLICY,
                        build.product(),
                        build.buildConfig(),
                        build.cdnConfig(),
                        build.region() == null ? "" : build.region(),
                        locale,
                        Long.toString(itemId),
                        Integer.toString(limit));
        return Json.sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String encodeCursor(Cursor cursor) {
        Objects.requireNonNull(cursor);
        var map = new LinkedHashMap<String, Object>();
        map.put("b", cursor.b());
        map.put("a", cursor.a());
        map.put("i", cursor.i());
        map.put("l", cursor.l());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(map));
    }

    public static Cursor decodeCursor(String value, String binding) {
        Objects.requireNonNull(value);
        try {
            if (!isWellFormedCursor(value)) {
                return null;
            }
            var map =
                    Json.object(
                            Json.parse(Base64.getUrlDecoder().decode(value), MAX_CURSOR_LENGTH));
            Object identity = map.get("i");
            Cursor cursor =
                    new Cursor(
                            Json.string(map.get("b")),
                            Json.number(map.get("a")),
                            identity == null ? null : Json.string(identity),
                            map.get("l") == null ? null : Json.string(map.get("l")));
            return !Objects.equals(cursor.b(), binding)
                            || cursor.a() < 0
                            || !encodeCursor(cursor).equals(value)
                    ? null
                    : cursor;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
