package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class NameCoverageTest {
    private static LoadedNames names(List<Long> ids, boolean damaged) {
        return new LoadedNames() {
            public CompletableFuture<String> nameOf(long id, Cancellation token) {
                throw new AssertionError("Coverage must merge pages, not look up each file");
            }

            public CompletableFuture<List<Long>> idsOf(String path, Cancellation token) {
                throw new AssertionError();
            }

            public CompletableFuture<List<NamesTypes.NameEntry>> from(
                    long from, int limit, Cancellation token) {
                return CompletableFuture.completedFuture(
                        ids.stream()
                                .filter(id -> id >= from)
                                .limit(limit)
                                .map(id -> new NamesTypes.NameEntry(id, "same-label"))
                                .toList());
            }

            public boolean isDamaged() {
                return damaged;
            }
        };
    }

    @Test
    void intersectsSelectedIdsAndCountsIdsRatherThanNames() {
        var ids = java.util.stream.LongStream.rangeClosed(1, 700).boxed().toList();
        assertEquals(
                new NameCoverage.Result(4, 3),
                NameCoverage.measure(
                        List.of(2L, 256L, 700L, 900L).iterator(),
                        names(ids, false),
                        Cancellation.NONE));
    }

    @Test
    void unknownNamesAreMeasuredZeroButDamagedSnapshotIsNot() {
        assertEquals(
                new NameCoverage.Result(2, 0),
                NameCoverage.measure(
                        List.of(1L, 2L).iterator(), names(List.of(), false), Cancellation.NONE));
        assertThrows(
                IllegalStateException.class,
                () ->
                        NameCoverage.measure(
                                List.of(1L).iterator(), names(List.of(), true), Cancellation.NONE));
    }

    @Test
    void duplicateFileIdsAndCanceledMeasurementsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        NameCoverage.measure(
                                List.of(1L, 1L).iterator(),
                                names(List.of(), false),
                                Cancellation.NONE));
        var token = new Cancellation();
        token.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> NameCoverage.measure(List.of(1L).iterator(), names(List.of(), false), token));
    }
}
