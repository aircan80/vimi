package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.files.names.LoadedNames;
import com.vibeminer.files.names.NamesTypes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FilesNamesWiringTest {
    private static final class Names implements FileNames, LoadedNames {
        final TreeMap<Long, String> names = new TreeMap<>();
        final AtomicInteger current = new AtomicInteger(),
                one = new AtomicInteger(),
                pages = new AtomicInteger();
        boolean enabled = true;

        @Override
        public CompletableFuture<NamesTypes.FileNamesStatus> status(Cancellation c) {
            return CompletableFuture.completedFuture(
                    new NamesTypes.FileNamesStatus(NamesTypes.FileNamesState.READY));
        }

        @Override
        public CompletableFuture<NamesTypes.FileNamesStatus> update(boolean force, Cancellation c) {
            return status(c);
        }

        @Override
        public CompletableFuture<LoadedNames> current(Cancellation c) {
            current.incrementAndGet();
            return CompletableFuture.completedFuture(enabled ? this : null);
        }

        @Override
        public CompletableFuture<String> nameOf(long id, Cancellation c) {
            one.incrementAndGet();
            return CompletableFuture.completedFuture(names.get(id));
        }

        @Override
        public CompletableFuture<List<Long>> idsOf(String path, Cancellation c) {
            return CompletableFuture.completedFuture(
                    names.entrySet().stream()
                            .filter(
                                    e ->
                                            BuildFilesHandle.fold(e.getValue())
                                                    .equals(BuildFilesHandle.fold(path)))
                            .map(Map.Entry::getKey)
                            .toList());
        }

        @Override
        public CompletableFuture<List<NamesTypes.NameEntry>> from(
                long from, int limit, Cancellation c) {
            pages.incrementAndGet();
            return CompletableFuture.completedFuture(
                    names.tailMap(from).entrySet().stream()
                            .limit(limit)
                            .map(e -> new NamesTypes.NameEntry(e.getKey(), e.getValue()))
                            .toList());
        }

        @Override
        public boolean isDamaged() {
            return false;
        }
    }

    private static FilesScenario scenario(Names names) {
        return new FilesScenario(
                false,
                new FileSystemOptions(
                        2,
                        Duration.ofMillis(1),
                        Clock.systemUTC(),
                        256L << 20,
                        TactKeys.NONE,
                        names));
    }

    private static BuildFiles open(FilesScenario s) {
        return s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
    }

    @Test
    void rootHashWinsWhileCommunityLabelIsReturned() {
        Names names = new Names();
        names.names.put(1L, "community/name.blp");
        names.names.put(2L, "root/path.blp");
        var s =
                scenario(names)
                        .add(1, "root/path.blp", new byte[] {1}, true, List.of())
                        .add(2, null, new byte[] {2}, true, List.of());
        try (var h = open(s)) {
            var stat = h.stat(FileRef.forPath("ROOT\\PATH.BLP"), Cancellation.NONE).join();
            assertEquals(1, stat.file().fileDataId());
            assertEquals("community/name.blp", stat.file().path());
            assertEquals(FilePathSource.LISTFILE, stat.file().pathSource());
        }
    }

    @Test
    void listfileFallbackPicksHighestIdInLocaleBeforeOtherLocales() {
        Names names = new Names();
        names.names.put(1L, "same/path");
        names.names.put(2L, "same/path");
        names.names.put(3L, "same/path");
        var s =
                scenario(names)
                        .add(1, null, new byte[] {1}, true, List.of())
                        .add(2, null, new byte[] {2}, true, List.of())
                        .add(3, null, new byte[] {3}, true, List.of());
        var e = s.encoded.get(3L);
        s.variants(
                3,
                List.of(
                        new FileVariant(
                                32,
                                0,
                                new com.vibeminer.network.CdnKey(FileFixtures.md5(new byte[] {3})),
                                1L,
                                e.key(),
                                (long) e.bytes().length,
                                null)));
        try (var h = open(s)) {
            assertEquals(
                    2,
                    h.stat(FileRef.forPath("SAME\\PATH"), Cancellation.NONE)
                            .join()
                            .file()
                            .fileDataId());
            assertTrue(h.exists(FileRef.forPath("same/path"), Cancellation.NONE).join());
        }
    }

    @Test
    void idReadsAndExistsNeverReadNames() {
        Names names = new Names();
        var s = scenario(names).add(1, null, new byte[] {1}, true, List.of());
        try (var h = open(s)) {
            h.exists(FileRef.id(1), Cancellation.NONE).join();
            h.get(FileRef.id(1), Cancellation.NONE).join();
            h.readRange(FileRef.id(1), 0, 1, Cancellation.NONE).join();
            try (var stream = h.openRead(FileRef.id(1), Cancellation.NONE).join().stream()) {
                assertEquals(1, stream.length());
            }
            assertEquals(0, names.current.get());
        }
    }

    @Test
    void listingWalksBoundedNamesPagesAndSwitchOffEndsPrefixAtBatchBoundary() {
        Names names = new Names();
        for (long id = 1; id <= 5; id++) {
            names.names.put(id, "folder/" + id);
        }
        var s = scenario(names);
        for (long id = 1; id <= 5; id++) {
            s.add(id, null, new byte[] {(byte) id}, false, List.of());
        }
        try (var h = open(s);
                var cursor =
                        h.list(
                                new FileFilter(0, 100, "FOLDER\\", null, null, null),
                                Cancellation.NONE)) {
            assertEquals(1, cursor.next(1, Cancellation.NONE).join().getFirst().fileDataId());
            names.enabled = false;
            assertEquals(
                    List.of(2L),
                    cursor.next(100, Cancellation.NONE).join().stream()
                            .map(FileTypes.BuildFile::fileDataId)
                            .toList());
            assertEquals(0, names.one.get());
            assertTrue(names.pages.get() <= 2);
        }
    }
}
