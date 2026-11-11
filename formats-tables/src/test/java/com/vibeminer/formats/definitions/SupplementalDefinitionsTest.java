package com.vibeminer.formats.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupplementalDefinitionsTest {
    private static final Cancellation TOKEN = Cancellation.NONE;
    private static final String ID = "b423fa7a";

    private static TableDefinitions.Resolution resolve(
            TableDefinitions service, String build, String layout) {
        return service.resolve(
                        ID, build, layout, WowLocale.EN_US, TableDefinitions.Lookup.LOCAL, TOKEN)
                .join();
    }

    @Test
    void exactSupplementIsOptInAndRetainsIndependentProvenance() {
        var bundled = BundledDefinitions.open(TOKEN).join();
        var original = new TableDefinitions(bundled);
        assertEquals(
                TableDefinitions.State.FETCHING_OFF,
                resolve(original, "1.60.1.70755", "4FDF0951").state());
        var enabled = original.withSupplementalDefinitions();
        for (String build :
                List.of("1.60.1.70707", "1.60.1.70753", "1.60.1.70755", "1.60.1.70790")) {
            for (String layout : new String[] {null, "4FDF0951", "4fdf0951"}) {
                var result = resolve(enabled, build, layout);
                assertEquals(TableDefinitions.State.RESOLVED, result.state());
                assertEquals(TableDefinitions.Source.SUPPLEMENTAL, result.definition().source());
                assertEquals(SupplementalDefinitions.REVISION, result.definition().revision());
                assertEquals(SupplementalDefinitions.SOURCE_URL, result.definition().sourceUrl());
                assertEquals(SupplementalDefinitions.SHA256, result.definition().sha256());
                assertEquals(
                        List.of(
                                "ID",
                                "Duration",
                                "DurationPerLevel",
                                "MaxDuration",
                                "DurationPerResource"),
                        result.schema().columns().stream()
                                .map(TableSchema.SchemaColumn::name)
                                .toList());
                assertEquals(TableSchema.SchemaMatch.BUILD, result.match());
                assertTrue(
                        result.schema().columns().stream()
                                .allMatch(
                                        c ->
                                                c.verifiedName()
                                                        && c.kind() == TableSchema.ColumnKind.INT));
                assertFalse(result.asked());
                assertNull(result.fetched());
                assertNull(result.because());
            }
        }
        var old = resolve(original, "1.15.2.53941", "5ECABAFD");
        var same = resolve(enabled, "1.15.2.53941", "5ECABAFD");
        assertEquals(old, same);
        assertEquals(TableDefinitions.Source.BUNDLED, same.definition().source());
        assertEquals(BundledDefinitions.REVISION, same.definition().revision());
    }

    @Test
    void wrongBuildLayoutAndManifestBindingNeverSelectTheSupplement() {
        var bundled = BundledDefinitions.open(TOKEN).join();
        var enabled = new TableDefinitions(bundled).withSupplementalDefinitions();
        // Builds the pinned document or the build list does not name stay without a definition.
        for (String unlisted : List.of("1.60.1.70754", "1.60.1.70800", "1.60.2.70790")) {
            assertEquals(
                    TableDefinitions.State.FETCHING_OFF,
                    resolve(enabled, unlisted, "4FDF0951").state(),
                    unlisted);
        }
        assertEquals(
                TableDefinitions.State.FETCHING_OFF,
                resolve(enabled, "1.60.1.70755", "4FDF0952").state());
        var wrong =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        ID, "SpellDuration", "999", true)),
                        Map.of(ID, bundled.require(ID)));
        assertEquals(
                TableDefinitions.State.FETCHING_OFF,
                resolve(
                                new TableDefinitions(wrong).withSupplementalDefinitions(),
                                "1.60.1.70755",
                                "4FDF0951")
                        .state());
    }

    @Test
    void supplementalResourceIsUnmodifiedAndRejectsTruncationOrCorruption() throws Exception {
        byte[] bytes;
        try (var input =
                SupplementalDefinitions.class.getResourceAsStream(
                        "supplemental/SpellDuration.dbd")) {
            bytes = input.readAllBytes();
        }
        assertTrue(SupplementalDefinitions.verify(bytes).listsBuild("1.60.1.70755", TOKEN));
        assertTrue(SupplementalDefinitions.verify(bytes).listsBuild("1.60.1.70790", TOKEN));
        assertThrows(
                DefinitionException.class,
                () ->
                        SupplementalDefinitions.verify(
                                java.util.Arrays.copyOf(bytes, bytes.length - 1)));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(DefinitionException.class, () -> SupplementalDefinitions.verify(bytes));
    }
}
