package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CreatureDataOnlyTest {
    @Test
    void tableDataRetainsModelReferenceWithoutAssetInspection() {
        var world =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                CreatureWorld.creature(
                                                        10, "Hogger", 100, 100, 0, -1))),
                                CreatureWorld.displays(List.of(CreatureWorld.display(100, "33"))),
                                CreatureWorld.models(List.of(CreatureWorld.model(33, "7001"))));
        try (var handle = world.open()) {
            var data =
                    handle.getData(10, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            assertEquals(7001L, data.detail().displays().getFirst().modelFileDataId());
            assertNull(data.detail().displays().getFirst().model());
            assertNotNull(data.detail().displays().getFirst().modelFacts());
            assertTrue(
                    handle.warmup(TableTypes.Priority.BACKGROUND, Cancellation.NONE)
                            .join()
                            .ready());
            var full = handle.get(10, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            assertEquals(data.detail().summary(), full.detail().summary());
            assertNotNull(full.detail().displays().getFirst().model());
        }
    }

    @Test
    void aDisplayNamedOutsideTheCreatureRecordResolvesTheSameChain() {
        var world =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                CreatureWorld.creature(
                                                        10, "Hogger", 100, 100, 0, -1))),
                                CreatureWorld.displays(List.of(CreatureWorld.display(100, "33"))),
                                CreatureWorld.models(List.of(CreatureWorld.model(33, "7001"))));
        try (var handle = world.open()) {
            var chain =
                    handle.displayChain(100, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals(7001L, chain.modelFileDataId());
            assertEquals(33L, chain.modelId());
            assertNull(chain.model());
            var declared =
                    handle.getData(10, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join()
                            .detail()
                            .displays()
                            .getFirst();
            assertEquals(declared.state(), chain.state());
            // A display with no CreatureDisplayInfo record stops there.
            assertEquals(
                    CreatureTypes.CreatureDisplayChainState.DISPLAY_ABSENT,
                    handle.displayChain(999, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join()
                            .state());
        }
    }
}
