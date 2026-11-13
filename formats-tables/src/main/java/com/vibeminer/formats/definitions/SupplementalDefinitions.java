package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.internal.Json;
import java.io.IOException;
import java.util.Set;

/** One explicit local supplemental profile; never replaces or relabels the pinned provider. */
public final class SupplementalDefinitions {
    public static final String REVISION = "86c0801007bbcb767f4bc275413d24bc6b2d486c";
    public static final String SHA256 =
            "ba38bda4fb8562d2fbdc571f92cfdd804051bd5ba76dced8817b41b9f5d266c3";
    public static final String IDENTITY = "spell-duration-4fdf0951-v3:" + REVISION + ":" + SHA256;
    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/wowdev/WoWDBDefs/"
                    + REVISION
                    + "/definitions/SpellDuration.dbd";
    private static final Set<String> BUILDS =
            Set.of(
                    "1.60.1.70707",
                    "1.60.1.70753",
                    "1.60.1.70755",
                    "1.60.1.70790",
                    "1.60.1.70840",
                    "1.60.1.70862",
                    "1.60.1.70888");

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
                TableDefinitions.Source.SUPPLEMENTAL, SHA256, SOURCE_URL, REVISION, document);
    }

    static DbdDocument verify(byte[] bytes) {
        if (bytes.length != 25974 || !Json.sha256(bytes).equals(SHA256)) {
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
