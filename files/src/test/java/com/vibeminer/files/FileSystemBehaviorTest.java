package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.builds.BuildTypes.FileVariant;
import com.vibeminer.files.FileTypes.EnsureOutcome;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileFormat;
import com.vibeminer.files.FileTypes.FileFormatSource;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class FileSystemBehaviorTest {
    private static FileSystemOptions options(long max) {
        return new FileSystemOptions(
                2, Duration.ofMillis(1), Clock.systemUTC(), max, TactKeys.NONE, FileNames.OFF);
    }

    private static BuildFiles open(FilesScenario s) {
        return s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
    }

    @Test
    void wholeReadLimitDoesNotPreventSmallRangeOrStream() {
        var s =
                new FilesScenario(false, options(4))
                        .add(1, null, new byte[] {1, 2, 3, 4, 5, 6}, true, List.of());
        try (var h = open(s)) {
            assertEquals(
                    FileReadState.TOO_LARGE,
                    h.get(FileRef.id(1), Cancellation.NONE).join().state());
            assertArrayEquals(
                    new byte[] {3, 4},
                    h.readRange(FileRef.id(1), 2, 2, Cancellation.NONE).join().bytes());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> h.readRange(FileRef.id(1), 0, 5, Cancellation.NONE));
            try (var stream = h.openRead(FileRef.id(1), Cancellation.NONE).join().stream()) {
                assertEquals(6, stream.length());
            }
        }
    }

    @Test
    void variantRankingPrefersOrdinaryThenHighResolutionThenLowViolence() {
        var s = new FilesScenario(false).add(1, null, new byte[] {1}, true, List.of());
        var ordinary = s.encoded.get(1L);
        var ckey = new CdnKey(FileFixtures.md5(new byte[] {1}));
        var low =
                new FileVariant(
                        2, 128, ckey, 1L, ordinary.key(), (long) ordinary.bytes().length, null);
        var high =
                new FileVariant(
                        2, 1, ckey, 1L, ordinary.key(), (long) ordinary.bytes().length, null);
        var normal =
                new FileVariant(
                        2, 0, ckey, 1L, ordinary.key(), (long) ordinary.bytes().length, null);
        assertEquals(normal, BuildFilesHandle.pick(List.of(low, high, normal), 2));
        assertEquals(high, BuildFilesHandle.pick(List.of(low, high), 2));
        assertEquals(low, BuildFilesHandle.pick(List.of(low), 2));
        assertEquals(null, BuildFilesHandle.pick(List.of(normal), 32));
    }

    @Test
    void listedFormatOnlyReadsStoredHeadAndRemembersIt() {
        byte[] data = {'B', 'L', 'P', '2', 1, 2, 3};
        var s =
                new FilesScenario(false)
                        .add(1, null, data, true, List.of())
                        .add(2, null, new byte[] {'B', 'L', 'P', '2', 9}, false, List.of());
        try (var h = open(s)) {
            assertEquals(
                    FileFormat.BLP,
                    h.stat(FileRef.id(1), Cancellation.NONE).join().file().format());
            assertEquals(
                    FileFormatSource.MAGIC,
                    h.stat(FileRef.id(1), Cancellation.NONE).join().file().formatSource());
            assertEquals(
                    FileFormat.UNKNOWN,
                    h.stat(FileRef.id(2), Cancellation.NONE).join().file().format());
            assertEquals(0, s.objects.transfers());
            var key = new ObjectKey(ObjectKey.ENCODED, s.encoded.get(1L).key().bytes());
            s.objects.remove(key);
            assertEquals(
                    FileFormat.BLP,
                    h.stat(FileRef.id(1), Cancellation.NONE).join().file().format());
        }
    }

    @Test
    void ensureMatchingDoesNotDecodeWhenNoFormatFilter() {
        byte[] data = {'B', 'L', 'P', '2', 1};
        var s =
                new FilesScenario(false)
                        .add(1, null, data, true, List.of())
                        .add(2, null, new byte[] {1, 2}, false, List.of())
                        .noEncoding(3);
        try (var h = open(s)) {
            var result =
                    h.ensure(
                                    EnsureSelection.matching(FileFilter.ALL),
                                    EnsurePriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertEquals(EnsureOutcome.INCOMPLETE, result.outcome());
            assertEquals(0, ((BuildFilesHandle) h).decodedChunks());
            assertEquals(
                    List.of(
                            FileEnsureState.ALREADY_STORED,
                            FileEnsureState.STORED,
                            FileEnsureState.NOT_IN_ENCODING),
                    result.files().stream().map(FileTypes.FileEnsured::state).toList());
        }
    }

    @Test
    void contentHashIsCheckedIncludingEmptyFiles() {
        var s = new FilesScenario(false).add(1, null, new byte[] {1, 2}, true, List.of());
        var e = s.encoded.get(1L);
        s.variants(
                1,
                List.of(
                        new FileVariant(
                                2,
                                0,
                                new CdnKey(new byte[16]),
                                2L,
                                e.key(),
                                (long) e.bytes().length,
                                null)));
        try (var h = open(s)) {
            assertEquals("BLTE_INTEGRITY", h.get(FileRef.id(1), Cancellation.NONE).join().code());
        }
        var empty = new FilesScenario(false).add(1, null, new byte[0], false, List.of());
        try (var h = open(empty)) {
            assertArrayEquals(new byte[0], h.get(FileRef.id(1), Cancellation.NONE).join().bytes());
            assertEquals(0, empty.objects.transfers());
        }
    }

    @Test
    void readFailuresPreserveLayerTwoCodeAndTimestamp() {
        for (var state :
                List.of(
                        ObjectState.NOT_FOUND,
                        ObjectState.NEEDS_NETWORK,
                        ObjectState.UNAVAILABLE,
                        ObjectState.REFUSED,
                        ObjectState.UNSUPPORTED,
                        ObjectState.STORE_FAILED)) {
            var s = new FilesScenario(false).add(1, null, new byte[] {1}, false, List.of());
            var e = s.encoded.get(1L);
            Instant at = Instant.parse("2020-01-01T00:00:00Z");
            s.objects.script(
                    new ObjectResult(
                            new ObjectKey(ObjectKey.ENCODED, e.key().bytes()),
                            state,
                            null,
                            null,
                            null,
                            null,
                            "TEST_FAILURE",
                            "why",
                            at));
            try (var h = open(s)) {
                var read = h.get(FileRef.id(1), Cancellation.NONE).join();
                assertEquals("TEST_FAILURE", read.code());
                assertEquals(at, read.observedAt());
                assertEquals(
                        switch (state) {
                            case NOT_FOUND -> FileReadState.MISSING;
                            case NEEDS_NETWORK -> FileReadState.NEEDS_NETWORK;
                            case UNAVAILABLE -> FileReadState.UNAVAILABLE;
                            case REFUSED -> FileReadState.CORRUPT;
                            case UNSUPPORTED -> FileReadState.UNSUPPORTED;
                            case STORE_FAILED -> FileReadState.STORE_FAILED;
                            default -> throw new AssertionError();
                        },
                        read.state());
            }
        }
    }

    @Test
    void streamCachesOneChunkAndRemainsUsableAfterReadFailure() {
        var s = new FilesScenario(false).add(1, null, new byte[] {1, 2, 3, 4}, true, List.of());
        try (var h = open(s);
                var stream = h.openRead(FileRef.id(1), Cancellation.NONE).join().stream()) {
            ByteBuffer b = ByteBuffer.allocate(1);
            assertEquals(1, stream.read(b, Cancellation.NONE).join());
            assertEquals(1, ((BuildFilesHandle) h).decodedChunks());
            b.clear();
            assertEquals(1, stream.read(b, Cancellation.NONE).join());
            assertEquals(1, ((BuildFilesHandle) h).decodedChunks());
            stream.seek(0);
            b.clear();
            assertEquals(1, stream.read(b, Cancellation.NONE).join());
            stream.seek(0);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> stream.read(ByteBuffer.allocate(2), token).join());
            assertEquals(0, stream.position());
        }
    }

    @Test
    void filtersComposeAndEmptyFormatsNeverRead() {
        var s =
                new FilesScenario(false)
                        .add(1, null, new byte[] {'B', 'L', 'P', '2'}, true, List.of())
                        .add(2, null, new byte[] {1}, false, List.of())
                        .noEncoding(3);
        try (var h = open(s);
                var cursor =
                        h.list(
                                new FileFilter(
                                        1,
                                        3,
                                        null,
                                        Set.of(FileFormat.BLP),
                                        Set.of(FileStorage.STORED),
                                        false),
                                Cancellation.NONE)) {
            assertEquals(
                    List.of(1L),
                    cursor.next(10, Cancellation.NONE).join().stream()
                            .map(FileTypes.BuildFile::fileDataId)
                            .toList());
        }
        try (var h = open(s);
                var empty =
                        h.list(
                                new FileFilter(0, 100, null, Set.of(), null, null),
                                Cancellation.NONE)) {
            assertTrue(empty.next(10, Cancellation.NONE).join().isEmpty());
        }
    }

    @Test
    void ensureFirstReportHasFinalListedTotalsAndFinalObserverFailureIsIsolated() {
        var s =
                new FilesScenario(false)
                        .add(1, null, new byte[] {1}, false, List.of())
                        .add(2, null, new byte[] {2}, false, List.of())
                        .add(3, null, new byte[] {3}, false, List.of());
        try (var h = open(s)) {
            List<FileTypes.EnsureProgress> reports = new ArrayList<>();
            var r =
                    h.ensure(
                                    EnsureSelection.many(
                                            List.of(FileRef.id(1), FileRef.id(2), FileRef.id(3))),
                                    EnsurePriority.INTERACTIVE,
                                    p -> {
                                        reports.add(p);
                                        throw new AssertionError("bad subscriber");
                                    },
                                    Cancellation.NONE)
                            .join();
            assertEquals(EnsureOutcome.COMPLETED, r.outcome());
            assertEquals(3, reports.getFirst().filesTotal());
            assertTrue(reports.getFirst().listed());
            assertEquals(r.progress(), reports.getLast());
        }
    }

    @Test
    void localeValidationAndMissingLocaleAreSeparate() {
        var s = new FilesScenario(false).add(1, null, new byte[] {1}, true, List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> s.files.forBuild(FilesScenario.REF, "bogus", Cancellation.NONE));
        assertEquals(
                FileTypes.FilesOpenState.LOCALE_NOT_IN_BUILD,
                s.files.forBuild(FilesScenario.REF, "frFR", Cancellation.NONE).join().state());
        try (var h =
                s.files.forBuild(FilesScenario.REF, "deDE", Cancellation.NONE).join().files()) {
            assertEquals(
                    FileReadState.NOT_IN_LOCALE,
                    h.get(FileRef.id(1), Cancellation.NONE).join().state());
        }
    }
}
