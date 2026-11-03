package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.network.CdnKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ContentInventoryTest {
    private static FileSystemOptions forbiddenNames() {
        var defaults = FileSystemOptions.defaults();
        var names =
                (FileNames)
                        Proxy.newProxyInstance(
                                FileNames.class.getClassLoader(),
                                new Class<?>[] {FileNames.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Content inventory must not consult names: "
                                                    + method.getName());
                                });
        return new FileSystemOptions(
                defaults.presenceBatch(),
                defaults.progressInterval(),
                defaults.clock(),
                defaults.maxReadBytes(),
                defaults.keys(),
                names);
    }

    private static ObjectSource forbiddenObjects() {
        return (ObjectSource)
                Proxy.newProxyInstance(
                        ObjectSource.class.getClassLoader(),
                        new Class<?>[] {ObjectSource.class},
                        (proxy, method, args) -> {
                            throw new AssertionError(
                                    "Content inventory must not consult presence/payload/network: "
                                            + method.getName());
                        });
    }

    @Test
    void boundedContentPagesNeverConsultNamesPresenceOrPayloadsAndPreserveUnknownSize() {
        var fixture =
                new FilesScenario(false, forbiddenNames(), ignored -> forbiddenObjects())
                        .add(1, "ignored.blp", new byte[] {1, 2}, true, List.of())
                        .add(2, null, new byte[] {3}, false, List.of(), FileLocales.bitOf("deDE"))
                        .noEncoding(3)
                        .add(4, null, new byte[] {4}, false, List.of());
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var cursor =
                        handle.listContentInventory(FileTypes.FileIdRange.ALL, Cancellation.NONE)) {
            var first = cursor.next(2, Cancellation.NONE).join();
            assertEquals(
                    List.of(1L, 3L),
                    first.stream().map(FileTypes.ContentFile::fileDataId).toList());
            assertEquals(2L, first.getFirst().size());
            assertNull(first.getLast().size());
            assertEquals("0".repeat(32), first.getLast().contentKey());
            assertEquals(
                    List.of(4L),
                    cursor.next(2, Cancellation.NONE).join().stream()
                            .map(FileTypes.ContentFile::fileDataId)
                            .toList());
            assertTrue(cursor.next(2, Cancellation.NONE).join().isEmpty());
        }
    }

    @Test
    void contentMatchesMetadataForLocaleAndVariantRankingWithoutPresenceQueries() {
        var presence = new AtomicInteger();
        var fixture =
                new FilesScenario(
                                false,
                                FileSystemOptions.defaults(),
                                source ->
                                        (ObjectSource)
                                                Proxy.newProxyInstance(
                                                        ObjectSource.class.getClassLoader(),
                                                        new Class<?>[] {ObjectSource.class},
                                                        (proxy, method, args) -> {
                                                            if (method.getName()
                                                                    .equals("hasKeys")) {
                                                                presence.incrementAndGet();
                                                            }
                                                            if (List.of(
                                                                            "getKey",
                                                                            "getKeys",
                                                                            "getRange")
                                                                    .contains(method.getName())) {
                                                                throw new AssertionError(
                                                                        "No payload reads");
                                                            }
                                                            try {
                                                                return method.invoke(source, args);
                                                            } catch (
                                                                    InvocationTargetException
                                                                            failure) {
                                                                throw failure.getCause();
                                                            }
                                                        }))
                        .add(1, null, new byte[] {1}, true, List.of())
                        .add(2, null, new byte[] {2}, false, List.of(), FileLocales.bitOf("deDE"))
                        .noEncoding(3)
                        .add(4, null, new byte[] {4}, false, List.of());
        var encoded = fixture.encoded.get(4L);
        var best = new CdnKey(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        fixture.variants(
                4,
                List.of(
                        new FileVariant(
                                2, 128, new CdnKey(new byte[16]), 88L, encoded.key(), 99L, null),
                        new FileVariant(2, 0, best, 77L, encoded.key(), 99L, null)));
        for (String locale : List.of("enUS", "deDE")) {
            try (var handle =
                            fixture.files
                                    .forBuild(FilesScenario.REF, locale, Cancellation.NONE)
                                    .join()
                                    .files();
                    var metadata =
                            handle.listMetadata(FileTypes.FileFilter.ALL, Cancellation.NONE);
                    var content =
                            handle.listContentInventory(
                                    FileTypes.FileIdRange.ALL, Cancellation.NONE)) {
                var expected =
                        metadata.next(10, Cancellation.NONE).join().stream()
                                .map(
                                        file ->
                                                new FileTypes.ContentFile(
                                                        file.fileDataId(),
                                                        file.size(),
                                                        file.contentKey()))
                                .toList();
                int before = presence.get();
                assertEquals(expected, content.next(10, Cancellation.NONE).join());
                assertEquals(before, presence.get());
                if (locale.equals("enUS")) {
                    assertEquals(best.hex(), expected.getLast().contentKey());
                    assertEquals(77L, expected.getLast().size());
                }
            }
        }
        assertTrue(presence.get() > 0, "Control metadata listing really queries presence");
    }

    @Test
    void rangesOrderingAndLimitsMatchExistingFileIdFilterSemantics() {
        var fixture =
                new FilesScenario(false, forbiddenNames(), ignored -> forbiddenObjects())
                        .add(1, null, new byte[] {1}, false, List.of())
                        .add(4, null, new byte[] {4}, false, List.of())
                        .add(0xffffffffL, null, new byte[] {5}, false, List.of());
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var range =
                        handle.listContentInventory(
                                new FileTypes.FileIdRange(4, 0xffffffffL), Cancellation.NONE);
                var inverted =
                        handle.listContentInventory(
                                new FileTypes.FileIdRange(5, 4), Cancellation.NONE)) {
            assertThrows(IllegalArgumentException.class, () -> range.next(0, Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class, () -> range.next(65537, Cancellation.NONE));
            assertEquals(
                    List.of(4L, 0xffffffffL),
                    range.next(10, Cancellation.NONE).join().stream()
                            .map(FileTypes.ContentFile::fileDataId)
                            .toList());
            assertTrue(inverted.next(1, Cancellation.NONE).join().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new FileTypes.FileIdRange(-1, 2));
        assertThrows(
                IllegalArgumentException.class, () -> new FileTypes.FileIdRange(0, 0x100000000L));
    }

    @Test
    void rejectedOperationAdmissionReleasesCursorForRetryWithoutAdvancingIds() throws Exception {
        var fixture =
                new FilesScenario(false, forbiddenNames(), ignored -> forbiddenObjects())
                        .add(1, null, new byte[] {1}, false, List.of());
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var cursor =
                        handle.listContentInventory(FileTypes.FileIdRange.ALL, Cancellation.NONE)) {
            var started = new java.util.concurrent.CountDownLatch(1024);
            var release = new java.util.concurrent.CountDownLatch(1);
            var pending = new ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
            try {
                for (int i = 0; i < 1024; i++) {
                    pending.add(
                            Io.async(
                                    () -> {
                                        started.countDown();
                                        release.await();
                                        return 1;
                                    }));
                }
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var rejected = cursor.next(1, Cancellation.NONE);
                var failure =
                        assertThrows(
                                java.util.concurrent.CompletionException.class, rejected::join);
                assertTrue(failure.getCause() instanceof FilesException);
                assertEquals("FILES_OPERATION_LIMIT", ((FilesException) failure.getCause()).code());
            } finally {
                release.countDown();
                java.util.concurrent.CompletableFuture.allOf(
                                pending.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .join();
            }
            assertEquals(1, cursor.next(1, Cancellation.NONE).join().getFirst().fileDataId());
            assertTrue(cursor.next(1, Cancellation.NONE).join().isEmpty());
        }
    }

    @Test
    void parentAndCallCancellationAndClosedCursorAreRespected() {
        var fixture =
                new FilesScenario(false, forbiddenNames(), ignored -> forbiddenObjects())
                        .add(1, null, new byte[] {1}, false, List.of());
        try (var handle =
                fixture.files
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .files()) {
            var parent = new Cancellation();
            try (var cursor = handle.listContentInventory(FileTypes.FileIdRange.ALL, parent)) {
                parent.cancel();
                assertThrows(
                        RuntimeException.class, () -> cursor.next(1, Cancellation.NONE).join());
            }
            try (var cursor =
                    handle.listContentInventory(FileTypes.FileIdRange.ALL, Cancellation.NONE)) {
                var call = new Cancellation();
                call.cancel();
                assertThrows(RuntimeException.class, () -> cursor.next(1, call).join());
            }
            var closed = handle.listContentInventory(FileTypes.FileIdRange.ALL, Cancellation.NONE);
            closed.close();
            assertThrows(IllegalStateException.class, () -> closed.next(1, Cancellation.NONE));
        }
    }
}
