package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.internal.Json;
import java.io.IOException;
import java.util.Set;

/** One explicit local supplemental profile; never replaces or relabels the pinned provider. */
public final class SupplementalDefinitions {
    public static final String REVISION = "93405a655ab55efdcc04ade02bc78f266b565650";
    public static final String SHA256 =
            "7bbfe3a9056372b7aef7e270e18eddaaefcc6da4f87cb81651114430508c944b";
    public static final String IDENTITY = "spell-duration-4fdf0951-v1:" + REVISION + ":" + SHA256;
    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/wowdev/WoWDBDefs/"
                    + REVISION
                    + "/definitions/SpellDuration.dbd";
    private static final Set<String> BUILDS =
            Set.of("1.60.1.70707", "1.60.1.70753", "1.60.1.70755");

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
        if (bytes.length != 25904 || !Json.sha256(bytes).equals(SHA256)) {
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
