package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.internal.Json;
import java.io.IOException;
import java.util.Set;

/** One explicit local supplemental profile; never replaces or relabels the pinned provider. */
public final class SupplementalDefinitions {
    public static final String REVISION = "07c3f55b3f6b666e0fca31dd80e6dbbf8a61003b";
    public static final String SHA256 =
            "2132081964f513ff61292f1ed6d3fcd5c424c27e04e14a68ce635b804457734b";
    public static final String IDENTITY = "spell-duration-4fdf0951-v2:" + REVISION + ":" + SHA256;
    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/wowdev/WoWDBDefs/"
                    + REVISION
                    + "/definitions/SpellDuration.dbd";
    private static final Set<String> BUILDS =
            Set.of("1.60.1.70707", "1.60.1.70753", "1.60.1.70755", "1.60.1.70790");

    private SupplementalDefinitions() {}

    private static final class Document {
        static final DbdDocument VALUE = load();
    }

    static TableDefinitions.Definition lookup(
            BundledDefinitions.DefinitionTable table,
            String build,
            String layout,
            Cancellation token) {
        token.check();
        if (!"b423fa7a".equals(table.tableId())
                || !"SpellDuration".equals(table.name())
                || !"1137828".equals(table.db2FileDataId())
                || !BUILDS.contains(build)
                || layout != null && !"4FDF0951".equalsIgnoreCase(layout)) {
            return null;
        }
        var document = Document.VALUE;
        if (!TableDefinitions.exact(document, build, layout, token)) {
            throw new DefinitionException(
                    "DEFINITION_CORRUPT", "Supplement does not cover its declared profile");
        }
        return new TableDefinitions.Definition(
                TableDefinitions.Source.SUPPLEMENTAL, SHA256, SOURCE_URL, REVISION, document, null);
    }

    static DbdDocument verify(byte[] bytes) {
        if (bytes.length != 25932 || !Json.sha256(bytes).equals(SHA256)) {
            throw new DefinitionException(
                    "DEFINITION_CORRUPT", "Supplemental definition failed pinned hash");
        }
        return DbdDefinitionReader.parse(bytes);
    }

    private static DbdDocument load() {
        try (var stream =
                SupplementalDefinitions.class.getResourceAsStream(
                        "supplemental/SpellDuration.dbd")) {
            if (stream == null) {
                throw new DefinitionException(
                        "DEFINITION_CORRUPT", "Missing supplemental definition");
            }
            byte[] bytes = stream.readNBytes(32769);
            return verify(bytes);
        } catch (IOException failure) {
            throw new DefinitionException(
                    "DEFINITION_CORRUPT", "Cannot read supplemental definition");
        }
    }
}
