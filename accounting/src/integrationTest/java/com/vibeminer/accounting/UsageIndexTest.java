package com.vibeminer.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.Cancellation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UsageIndexTest {
    @TempDir Path folder;

    private static UsageIndex.Resource original(String key, long bytes) {
        return new UsageIndex.Resource("objects", key, UsageIndex.Role.BLIZZARD_ENCODED, bytes);
    }

    private static UsageIndex.Inventory inventory(List<UsageIndex.Resource> rows) {
        return () ->
                new UsageIndex.Cursor() {
                    int offset;

                    public List<UsageIndex.Resource> next(Cancellation token) {
                        token.check();
                        int end = Math.min(rows.size(), offset + 256);
                        var result = List.copyOf(rows.subList(offset, end));
                        offset = end;
                        return result;
                    }
                };
    }

    private static UsageIndex.Presence lengths(Map<String, Long> values) {
        return (rows, token) ->
                rows.stream().map(row -> values.getOrDefault(row.key(), -1L)).toList();
    }

    private static void retain(UsageIndex index, String owner) {
        index.retain(owner, false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED).join();
    }

    private static void publish(
            UsageIndex index,
            String owner,
            List<UsageIndex.Resource> rows,
            Map<String, Long> lengths) {
        index.replace(owner, "encoding", "v1", inventory(rows), lengths(lengths), Cancellation.NONE)
                .join();
    }

    @Test
    void sharingSurvivesReopenAndClosingHandlesDoesNotChangeMembership() {
        Path path = folder.resolve("usage.sqlite");
        var index = UsageIndex.open(path).join();
        retain(index, "a");
        retain(index, "b");
        publish(
                index,
                "a",
                List.of(original("shared", 10), original("only-a", 20)),
                Map.of("shared", 10L, "only-a", 20L));
        assertEquals(1, index.figures("a").join().incompleteBuilds());
        publish(index, "b", List.of(original("shared", 10)), Map.of("shared", 10L));
        assertEquals(new UsageIndex.Split(20, 10, 0, 0), index.figures("a").join().original());
        index.closeAsync().join();
        index = UsageIndex.open(path).join();
        try {
            var measured = index.figures("a").join();
            assertEquals(2, measured.retainedBuilds());
            assertEquals(0, measured.incompleteBuilds());
            assertEquals(10, measured.original().shared());
            index.forget("b").join();
            assertEquals(30, index.figures("a").join().original().onlyThisBuild());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void duplicateRootsDeduplicatePerBuildAndProvenanceRemainsSeparate() {
        var index = UsageIndex.open(folder.resolve("usage.sqlite")).join();
        try {
            for (String owner : List.of("a", "b")) {
                retain(index, owner);
                publish(index, owner, List.of(), Map.of());
                for (String producer : List.of("tables", "files")) {
                    index.retain(owner, false, producer, UsageIndex.Role.DERIVED).join();
                    index.replace(
                                    owner,
                                    producer,
                                    "v1",
                                    inventory(
                                            List.of(
                                                    new UsageIndex.Resource(
                                                            "generated",
                                                            "segment",
                                                            UsageIndex.Role.DERIVED,
                                                            -1))),
                                    lengths(Map.of("segment", 37L)),
                                    Cancellation.NONE)
                            .join();
                }
            }
            assertEquals(37, index.figures("a").join().derived().shared());
            index.retain("community", true, "names", UsageIndex.Role.PROVIDER).join();
            index.replace(
                            "community",
                            "names",
                            "v1",
                            inventory(
                                    List.of(
                                            new UsageIndex.Resource(
                                                    "objects",
                                                    "listfile",
                                                    UsageIndex.Role.PROVIDER,
                                                    -1))),
                            lengths(Map.of("listfile", 1000L)),
                            Cancellation.NONE)
                    .join();
            assertEquals(0, index.figures("a").join().original().onlyThisBuild());
            assertTrue(index.figures("a").join().derivedComplete());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void failedAndCanceledImportKeepsPreviousCompleteGeneration() {
        var index = UsageIndex.open(folder.resolve("usage.sqlite")).join();
        try {
            retain(index, "a");
            publish(index, "a", List.of(original("old", 5)), Map.of("old", 5L));
            var cancel = new Cancellation();
            var rows = new ArrayList<UsageIndex.Resource>();
            for (int i = 0; i < 300; i++) {
                rows.add(original("new-" + i, 1));
            }
            UsageIndex.Presence interrupted =
                    (page, token) -> {
                        if (page.getFirst().key().equals("new-256")) {
                            cancel.cancel();
                        }
                        return page.stream().map(row -> 1L).toList();
                    };
            assertThrows(
                    RuntimeException.class,
                    () ->
                            index.replace(
                                            "a",
                                            "encoding",
                                            "v2",
                                            inventory(rows),
                                            interrupted,
                                            cancel)
                                    .join());
            assertEquals(5, index.figures("a").join().original().onlyThisBuild());
            assertTrue(index.current("a", "encoding", "v1").join());
            assertFalse(index.current("a", "encoding", "v2").join());
            assertThrows(
                    CompletionException.class,
                    () ->
                            index.replace(
                                            "a",
                                            "encoding",
                                            "v3",
                                            () ->
                                                    new UsageIndex.Cursor() {
                                                        public List<UsageIndex.Resource> next(
                                                                Cancellation token) {
                                                            throw new IllegalStateException(
                                                                    "producer failed");
                                                        }
                                                    },
                                            lengths(Map.of()),
                                            Cancellation.NONE)
                                    .join());
            assertEquals(5, index.figures("a").join().original().onlyThisBuild());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void reconciliationHandlesMissedEventsDeletesAndDeclaredLengthDiscrepancies() {
        var index = UsageIndex.open(folder.resolve("usage.sqlite")).join();
        try {
            retain(index, "a");
            publish(index, "a", List.of(original("x", 10), original("y", 20)), Map.of("x", 9L));
            var initial = index.figures("a").join();
            assertEquals(9, initial.original().onlyThisBuild());
            assertEquals(30, initial.publishedBytes());
            assertEquals(1, initial.sizeMismatches());
            index.refresh("objects", lengths(Map.of("y", 20L)), Cancellation.NONE).join();
            assertEquals(20, index.figures("a").join().original().onlyThisBuild());
            index.update("objects", List.of("y"), lengths(Map.of()), Cancellation.NONE).join();
            assertEquals(0, index.figures("a").join().original().onlyThisBuild());
            assertEquals(2, index.figures("a").join().publishedObjects());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void incompleteInventoriesAndOversizedPagesNeverClaimCompleteCoverage() {
        var index = UsageIndex.open(folder.resolve("usage.sqlite")).join();
        try {
            retain(index, "a");
            retain(index, "b");
            publish(index, "a", List.of(original("x", 1)), Map.of("x", 1L));
            index.retain("a", false, "table-graphs", UsageIndex.Role.DERIVED).join();
            assertEquals(1, index.figures("a").join().incompleteBuilds());
            assertFalse(index.figures("a").join().derivedComplete());
            assertThrows(
                    CompletionException.class,
                    () ->
                            index.replace(
                                            "b",
                                            "encoding",
                                            "v1",
                                            () ->
                                                    new UsageIndex.Cursor() {
                                                        public List<UsageIndex.Resource> next(
                                                                Cancellation token) {
                                                            return java.util.Collections.nCopies(
                                                                    257, original("x", 1));
                                                        }
                                                    },
                                            lengths(Map.of()),
                                            Cancellation.NONE)
                                    .join());
            assertEquals(1, index.figures("a").join().incompleteBuilds());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void nameCoverageCacheRequiresTheExactBuildLocaleAndProviderFingerprint() {
        Path path = folder.resolve("usage.sqlite");
        var index = UsageIndex.open(path).join();
        retain(index, "a");
        index.saveCoverage("a", "enUS:catalog-v1", "sha-a", 10, 4, Cancellation.NONE).join();
        index.closeAsync().join();
        index = UsageIndex.open(path).join();
        try {
            assertEquals(
                    new UsageIndex.Coverage(10, 4),
                    index.coverage("a", "enUS:catalog-v1", "sha-a").join());
            assertEquals(null, index.coverage("a", "enUS:catalog-v1", "sha-b").join());
            assertEquals(null, index.coverage("a", "deDE:catalog-v1", "sha-a").join());
            index.forget("a").join();
            assertEquals(null, index.coverage("a", "enUS:catalog-v1", "sha-a").join());
        } finally {
            index.closeAsync().join();
        }
    }

    @Test
    void boundedAdmissionAndCloseDrainEveryAcceptedRequest() throws Exception {
        var index = UsageIndex.open(folder.resolve("usage.sqlite")).join();
        retain(index, "a");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var publishing =
                index.replace(
                        "a",
                        "encoding",
                        "v1",
                        inventory(List.of(original("x", 1))),
                        (rows, token) -> {
                            entered.countDown();
                            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Release timed out");
                            }
                            return List.of(1L);
                        },
                        Cancellation.NONE);
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var queued =
                    new ArrayList<java.util.concurrent.CompletableFuture<UsageIndex.Figures>>();
            for (int i = 0; i < 100; i++) {
                queued.add(index.figures("a"));
            }
            assertTrue(
                    queued.stream()
                            .anyMatch(
                                    java.util.concurrent.CompletableFuture
                                            ::isCompletedExceptionally),
                    "Requests beyond the 64-slot queue must fail promptly");
            var closing = index.closeAsync();
            assertFalse(closing.isDone());
            release.countDown();
            publishing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            closing.get(10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.CompletableFuture.allOf(
                            queued.stream()
                                    .map(future -> future.handle((value, error) -> null))
                                    .toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(queued.stream().allMatch(java.util.concurrent.CompletableFuture::isDone));
            assertThrows(CompletionException.class, () -> index.figures("a").join());
        } finally {
            release.countDown();
            index.closeAsync().join();
        }
    }
}
