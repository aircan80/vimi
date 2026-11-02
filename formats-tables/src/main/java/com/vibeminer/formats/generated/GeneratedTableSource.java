package com.vibeminer.formats.generated;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Content/definition/options identity shared across builds, scoped only for encrypted encodings.
 */
public record GeneratedTableSource(
        String locale,
        String contentKey,
        String definition,
        Db2Types.Limits readerLimits,
        String encodingScope) {
    public static final String IDENTITY_FORMAT = "vibeminer-generated-table v2",
            READER_REVISION = "db2-values-v3",
            ROW_ENCODING = "rows-v1";

    public GeneratedTableSource {
        if (locale != null) {
            WowLocale.parse(locale);
        }
        if (contentKey == null
                || !contentKey.matches("[0-9a-f]{32}")
                || definition == null
                || !definition.matches("[ -~]{1,512}")
                || encodingScope != null && !encodingScope.matches("[ -~]{1,512}")) {
            throw new IllegalArgumentException("Invalid generated source identity");
        }
        java.util.Objects.requireNonNull(readerLimits);
    }

    public static GeneratedTableSource forBlock(
            Block block, String locale, String contentKey, Db2Types.Limits limits, String scope) {
        return new GeneratedTableSource(
                ResolvedDefinition.hasLocstrings(block) ? locale : null,
                contentKey,
                ResolvedDefinition.hash(block),
                limits,
                scope);
    }

    public String readerOptions() {
        var map = new LinkedHashMap<>(Json.object(Json.parse(Json.bytes(readerLimits), 1 << 20)));
        return Json.sha256(Json.bytes(map));
    }

    public String identityText() {
        return String.join(
                "\n",
                IDENTITY_FORMAT,
                locale == null ? "-" : locale,
                contentKey,
                definition,
                "reader-options=" + readerOptions(),
                READER_REVISION,
                ROW_ENCODING);
    }

    public String scopedIdentityText() {
        return encodingScope == null ? null : identityText() + "\nencoding-in=" + encodingScope;
    }

    public String baseHash(boolean scoped) {
        String value = scoped ? scopedIdentityText() : identityText();
        if (value == null) {
            throw new IllegalArgumentException("Encrypted source requires encoding scope");
        }
        return Json.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
