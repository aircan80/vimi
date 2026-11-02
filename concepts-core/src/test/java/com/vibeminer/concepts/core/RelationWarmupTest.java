package com.vibeminer.concepts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import org.junit.jupiter.api.Test;

final class RelationWarmupTest {
    @Test
    void preparedIndexIsReusedAndBudgetRefusalRemainsExplicit() {
        var source = new RelationIndexesTest.Source().add(1, "42").add(2, "42");
        var index = new RelationIndexes(source.tables(), 2);
        assertTrue(
                index.prepare("Links", "Parent", RelationIndexesTest.PRIORITY, Cancellation.NONE)
                        .join()
                        .complete());
        assertEquals(
                2,
                index.lookup(
                                "Links",
                                "Parent",
                                42,
                                10,
                                RelationIndexesTest.PRIORITY,
                                Cancellation.NONE)
                        .join()
                        .matches());
        assertEquals(1, index.builds());
        var limited = new RelationIndexes(source.tables(), 1);
        assertEquals(
                RelationIndexes.State.INDEX_LIMIT,
                limited.prepare("Links", "Parent", RelationIndexesTest.PRIORITY, Cancellation.NONE)
                        .join()
                        .state());
        assertEquals(0, limited.entries());
    }

    @Test
    void cancellationPreventsWarmupWork() {
        var source = new RelationIndexesTest.Source().add(1, "1");
        var token = new Cancellation();
        token.cancel();
        var index = new RelationIndexes(source.tables());
        assertThrows(
                Exception.class,
                () -> index.prepare("Links", "Parent", RelationIndexesTest.PRIORITY, token).join());
        assertEquals(0, index.builds());
    }
}
