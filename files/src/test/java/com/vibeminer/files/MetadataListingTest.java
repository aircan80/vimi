package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.files.names.LoadedNames;
import com.vibeminer.files.names.NamesTypes;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MetadataListingTest {
    private static final byte[] BLP = {'B', 'L', 'P', '2', 0, 0, 0, 0};

    private static ObjectSource guarded(ObjectSource source, AtomicInteger payloads) {
        return (ObjectSource)
                Proxy.newProxyInstance(
                        ObjectSource.class.getClassLoader(),
                        new Class<?>[] {ObjectSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getKey")
                                    || method.getName().equals("getKeys")
                                    || method.getName().equals("getRange")) {
                                payloads.incrementAndGet();
                                throw new AssertionError(
                                        "Metadata listing cannot acquire encoded bytes or BLTE headers");
                            }
                            try {
                                return method.invoke(source, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    @Test
    void metadataPagesUseOnlyIndexAndPresenceIncludingLocaleSizeAndEncryption() {
        var payloads = new AtomicInteger();
        var fixture =
                new FilesScenario(
                                false,
                                FileSystemOptions.defaults(),
                                source -> guarded(source, payloads))
                        .add(1, "ignored/root.blp", BLP, true, List.of())
                        .add(2, null, new byte[] {7}, false, List.of())
                        .add(
                                3,
                                null,
                                BLP,
                                true,
                                List.of(new FileTypes.FileHole(0, BLP.length, "1122334455667788")))
                        .add(4, null, new byte[] {9, 8}, false, List.of());
        var encoded = fixture.encoded.get(2L);
        fixture.variants(
                2,
                List.of(
                        new FileVariant(
                                16,
                                0,
                                new com.vibeminer.network.CdnKey(FileFixtures.md5(BLP)),
                                1L,
                                encoded.key(),
                                (long) encoded.bytes().length,
                                null)));
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var cursor = handle.listMetadata(FileFilter.ALL, Cancellation.NONE)) {
            var page = cursor.next(10, Cancellation.NONE).join();
            assertEquals(3, page.size());
            assertEquals(
                    List.of(1L, 3L, 4L),
                    page.stream().map(FileTypes.BuildFile::fileDataId).toList());
            assertTrue(page.get(1).encrypted());
            assertEquals(FileStorage.NOT_STORED, page.get(2).storage());
            var file = page.getFirst();
            assertEquals(1, file.fileDataId());
            assertEquals(8L, file.size());
            assertEquals(FileStorage.STORED, file.storage());
            assertEquals(FileFormat.UNKNOWN, file.format());
            assertEquals(FileFormatSource.NONE, file.formatSource());
            assertTrue(cursor.next(10, Cancellation.NONE).join().isEmpty());
            assertEquals(0, payloads.get());
        }
    }

    @Test
    void explicitFormatFiltersNeverEnableSniffingAndExistingListRemainsContentAware() {
        var fixture = new FilesScenario(false).add(1, null, BLP, true, List.of());
        try (var handle =
                fixture.files
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .files()) {
            try (var full = handle.list(FileFilter.ALL, Cancellation.NONE)) {
                var file = full.next(10, Cancellation.NONE).join().getFirst();
                assertEquals(FileFormat.BLP, file.format());
                assertEquals(FileFormatSource.MAGIC, file.formatSource());
            }
            var unknownOnly =
                    new FileFilter(0, 0xffffffffL, null, Set.of(FileFormat.UNKNOWN), null, null);
            try (var metadata = handle.listMetadata(unknownOnly, Cancellation.NONE)) {
                assertEquals(
                        FileFormat.UNKNOWN,
                        metadata.next(10, Cancellation.NONE).join().getFirst().format(),
                        "Even cached magic is not a metadata-only type fact");
            }
            try (var metadata =
                    handle.listMetadata(
                            new FileFilter(
                                    0, 0xffffffffL, null, Set.of(FileFormat.BLP), null, null),
                            Cancellation.NONE)) {
                assertTrue(metadata.next(10, Cancellation.NONE).join().isEmpty());
            }
        }
    }

    @Test
    void availableNamesAllowExtensionClassificationWithoutPayloadReads() {
        var payloads = new AtomicInteger();
        LoadedNames loaded =
                new LoadedNames() {
                    public CompletableFuture<String> nameOf(long id, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                id == 1 ? "icons/example.blp" : null);
                    }

                    public CompletableFuture<List<Long>> idsOf(String path, Cancellation token) {
                        return CompletableFuture.completedFuture(List.of(1L));
                    }

                    public CompletableFuture<List<NamesTypes.NameEntry>> from(
                            long from, int limit, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                from <= 1
                                        ? List.of(new NamesTypes.NameEntry(1, "icons/example.blp"))
                                        : List.of());
                    }

                    public boolean isDamaged() {
                        return false;
                    }
                };
        FileNames names =
                new FileNames() {
                    public CompletableFuture<NamesTypes.FileNamesStatus> status(
                            Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new NamesTypes.FileNamesStatus(NamesTypes.FileNamesState.READY));
                    }

                    public CompletableFuture<NamesTypes.FileNamesStatus> update(
                            boolean force, Cancellation token) {
                        throw new AssertionError("Listing never refreshes names");
                    }

                    public CompletableFuture<LoadedNames> current(Cancellation token) {
                        return CompletableFuture.completedFuture(loaded);
                    }
                };
        var defaults = FileSystemOptions.defaults();
        var options =
                new FileSystemOptions(
                        defaults.presenceBatch(),
                        defaults.progressInterval(),
                        defaults.clock(),
                        defaults.maxReadBytes(),
                        defaults.keys(),
                        names);
        var fixture =
                new FilesScenario(false, options, source -> guarded(source, payloads))
                        .add(1, null, BLP, true, List.of());
        var filter =
                new FileFilter(
                        0,
                        0xffffffffL,
                        "icons/",
                        Set.of(FileFormat.BLP),
                        Set.of(FileStorage.STORED),
                        false);
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var cursor = handle.listMetadata(filter, Cancellation.NONE)) {
            var file = cursor.next(10, Cancellation.NONE).join().getFirst();
            assertEquals(FileFormat.BLP, file.format());
            assertEquals(FileFormatSource.EXTENSION, file.formatSource());
            assertEquals("icons/example.blp", file.path());
            assertEquals(0, payloads.get());
        }
    }

    @Test
    void seededFakeSupportsMetadataOnlyAndDefaultProvidersRefuseUnsafeFallback() {
        var fixture = new FilesScenario(true).add(1, null, BLP, true, List.of());
        try (var handle =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files();
                var cursor = handle.listMetadata(FileFilter.ALL, Cancellation.NONE)) {
            assertEquals(
                    FileFormat.UNKNOWN,
                    cursor.next(10, Cancellation.NONE).join().getFirst().format());
        }
        BuildFiles legacy =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.isDefault()) {
                                        return java.lang.reflect.InvocationHandler.invokeDefault(
                                                proxy, method, args);
                                    }
                                    throw new AssertionError(
                                            "Default metadata listing cannot delegate to payload-aware list");
                                });
        assertThrows(
                UnsupportedOperationException.class,
                () -> legacy.listMetadata(FileFilter.ALL, Cancellation.NONE));
    }
}
