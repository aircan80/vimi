package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import org.junit.jupiter.api.Test;

final class EffectsWarmupTest {
    @Test
    void reviewedBridgeIndexCanBePreparedWithoutAnItemLookup() {
        var world =
                new EffectsWorld()
                        .with(
                                EffectsWorld.retail()
                                        .toArray(
                                                com.vibeminer.formats.tables.MemoryTables.FakeTable
                                                                []
                                                        ::new));
        try (var handle = world.open()) {
            var result = handle.warmup(EffectsWorld.NOW, Cancellation.NONE).join();
            assertTrue(result.ready());
            assertEquals(1, result.entries().size());
            assertEquals("ItemXItemEffect", result.entries().getFirst().table());
            assertTrue(handle.warmup(EffectsWorld.NOW, Cancellation.NONE).join().ready());
        }
    }
}
