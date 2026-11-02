package com.vibeminer.formats.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import org.junit.jupiter.api.Test;

final class BundledDefinitionsTest {
    @Test
    void completePinnedManifestAndReviewedCreatureDefinitionVerify() {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        assertEquals(1346, provider.tables().size());
        assertTrue(
                provider.tables().stream().allMatch(BundledDefinitions.DefinitionTable::bundled));
        var creature = provider.require("c9d6b6b3");
        assertEquals(
                "3644a603aae0eed595de31933c49e7bd8abea32bc1a1c32f9d754afbfb8f5682",
                creature.sha256());
        assertTrue(creature.sourceUrl().contains(BundledDefinitions.REVISION));
        assertNotNull(creature.document());
    }

    @Test
    void everyPinnedDefinitionRemainsIndividuallyBounded() {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        int described = 0, unsupported = 0;
        for (var table : provider.tables()) {
            var result = provider.tryGet(table.tableId());
            if (result.definition() != null) {
                described++;
                assertTrue(result.definition().document().selectionCost() <= 4L << 20);
            } else {
                unsupported++;
                assertNotNull(result.failure());
                assertEquals("DEFINITION_UNSUPPORTED", result.failure().code());
            }
        }
        assertTrue(described > 1200);
        assertEquals(1346, described + unsupported);
    }
}
