package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.files.names.LoadedNames;
import com.vibeminer.files.names.NamesTypes;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class StatContentTest {
    private static final class Names implements FileNames, LoadedNames {
        final Map<Long, String> names;
        final AtomicInteger nameOf = new AtomicInteger();

        Names(Map<Long, String> names) {
            this.names = names;
        }

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
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<String> nameOf(long id, Cancellation c) {
            nameOf.incrementAndGet();
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
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public boolean isDamaged() {
            return false;
        }
    }

    private static final byte[] BLP = blp(1);

    /** Distinct payloads, so no two entries share an encoded object. */
    private static byte[] blp(int n) {
        return new byte[] {'B', 'L', 'P', '2', (byte) n, 2, 3};
    }

    private static FilesScenario scenario(Names names) {
        var s =
                new FilesScenario(
                        false,
                        new FileSystemOptions(
                                2,
                                Duration.ofMillis(1),
                                Clock.systemUTC(),
                                256L << 20,
                                TactKeys.NONE,
                                names));
        // 1 stored, 2 remote only, 3 no encoded copy, 4 other locale only, 5 stored but its
        // payload is garbage, 6 stored with an encrypted hole under an unknown key, 7 stored
        // with a root path, 99 unknown.
        s.add(1, null, BLP, true, List.of())
                .add(2, null, blp(2), false, List.of())
                .noEncoding(3)
                .add(4, null, blp(4), true, List.of(), FileLocales.bitOf("deDE"))
                .add(5, null, blp(5), true, List.of())
                .add(6, null, new byte[64], true, List.of(new FileHole(0, 32, "0123456789abcdef")))
                .add(7, "root/seven.blp", blp(7), true, List.of());
        s.objects.put(
                new ObjectKey(ObjectKey.ENCODED, s.encoded.get(5L).key().bytes()),
                new byte[] {9, 9, 9, 9});
        return s;
    }

    private static BuildFiles open(FilesScenario s) {
        return s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
    }

    private static void assertSameContentFacts(FileStat full, FileStat light) {
        assertEquals(full.state(), light.state());
        if (full.file() == null) {
            assertNull(light.file());
            return;
        }
        assertNotNull(light.file());
        assertEquals(full.file().fileDataId(), light.file().fileDataId());
        assertEquals(full.file().contentKey(), light.file().contentKey());
        assertEquals(full.file().storage(), light.file().storage());
        assertEquals(full.file().size(), light.file().size());
        assertEquals(full.file().encrypted(), light.file().encrypted());
        assertNull(light.file().path());
        assertEquals(FilePathSource.NONE, light.file().pathSource());
        assertEquals(FileFormat.UNKNOWN, light.file().format());
        assertEquals(FileFormatSource.NONE, light.file().formatSource());
    }

    @Test
    void contentStatMatchesStatForPresentMissingAndUnreadableEntries() {
        var names = new Names(Map.of(1L, "named/one.blp", 7L, "named/seven.blp"));
        var refs =
                List.of(
                        FileRef.id(1),
                        FileRef.id(2),
                        FileRef.id(3),
                        FileRef.id(4),
                        FileRef.id(5),
                        FileRef.id(6),
                        FileRef.id(7),
                        FileRef.id(99),
                        FileRef.forPath("ROOT\\SEVEN.BLP"),
                        FileRef.forPath("named/one.blp"),
                        FileRef.forPath("missing/path.blp"));
        try (var h = open(scenario(names))) {
            for (var ref : refs) {
                var light = h.statContent(ref, Cancellation.NONE).join();
                assertSameContentFacts(h.stat(ref, Cancellation.NONE).join(), light);
            }
            assertEquals(
                    FileLookupState.FOUND,
                    h.statContent(FileRef.id(1), Cancellation.NONE).join().state());
            assertEquals(
                    FileStorage.NOT_STORED,
                    h.statContent(FileRef.id(2), Cancellation.NONE).join().file().storage());
            assertEquals(
                    FileLookupState.NOT_IN_ENCODING,
                    h.statContent(FileRef.id(3), Cancellation.NONE).join().state());
            assertEquals(
                    FileLookupState.NOT_IN_LOCALE,
                    h.statContent(FileRef.id(4), Cancellation.NONE).join().state());
            assertEquals(
                    FileLookupState.UNKNOWN_FILE,
                    h.statContent(FileRef.id(99), Cancellation.NONE).join().state());
        }
    }

    @Test
    void contentStatNeverLooksUpNamesOrReadsPayloads() {
        var names = new Names(Map.of(1L, "named/one.blp"));
        var s = scenario(names);
        try (var h = open(s)) {
            for (long id = 1; id <= 7; id++) {
                h.statContent(FileRef.id(id), Cancellation.NONE).join();
            }
            assertEquals(0, names.nameOf.get());
            assertEquals(0, ((BuildFilesHandle) h).decodedChunks());
            assertEquals(0, s.objects.transfers());
            // The full stat still names and sniffs, so the comparison above is meaningful.
            var full = h.stat(FileRef.id(1), Cancellation.NONE).join().file();
            assertEquals("named/one.blp", full.path());
            assertEquals(FileFormat.BLP, full.format());
        }
    }

    @Test
    void defaultContentStatDelegatesToStat() {
        var s = new FilesScenario(true).add(1, null, BLP, true, List.of());
        try (var h = open(s)) {
            assertEquals(
                    h.stat(FileRef.id(1), Cancellation.NONE).join(),
                    h.statContent(FileRef.id(1), Cancellation.NONE).join());
        }
    }

    @Test
    void contentKeyIsTheSelectedVariant() {
        var s = scenario(new Names(Map.of()));
        var e = s.encoded.get(1L);
        var other = new CdnKey(FileFixtures.md5(new byte[] {42}));
        s.variants(
                1,
                List.of(
                        new FileVariant(
                                2,
                                128,
                                other,
                                (long) BLP.length,
                                e.key(),
                                (long) e.bytes().length,
                                null),
                        new FileVariant(
                                2,
                                0,
                                new CdnKey(FileFixtures.md5(BLP)),
                                (long) BLP.length,
                                e.key(),
                                (long) e.bytes().length,
                                null)));
        try (var h = open(s)) {
            assertSameContentFacts(
                    h.stat(FileRef.id(1), Cancellation.NONE).join(),
                    h.statContent(FileRef.id(1), Cancellation.NONE).join());
            assertEquals(
                    new CdnKey(FileFixtures.md5(BLP)).toString(),
                    h.statContent(FileRef.id(1), Cancellation.NONE).join().file().contentKey());
        }
    }
}
