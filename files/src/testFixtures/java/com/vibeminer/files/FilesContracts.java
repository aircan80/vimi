package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.EnsureOutcome;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileLookupState;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStorage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
public abstract class FilesContracts {
    protected abstract FilesScenario create();

    private static final byte[] DATA = "BLP2abcdefghijklmno".getBytes(StandardCharsets.US_ASCII);

    private BuildFiles open(FilesScenario s) {
        return s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
    }

    @Test
    void statAndExistsDoNotFetchAndWholeReadStores() {
        var s = create().add(10, null, DATA, false, List.of());
        try (var h = open(s)) {
            assertTrue(h.exists(FileRef.id(10), Cancellation.NONE).join());
            assertFalse(h.exists(FileRef.id(999), Cancellation.NONE).join());
            assertEquals(
                    FileStorage.NOT_STORED,
                    h.stat(FileRef.id(10), Cancellation.NONE).join().file().storage());
            assertEquals(0, s.objects.transfers());
            assertArrayEquals(DATA, h.get(FileRef.id(10), Cancellation.NONE).join().bytes());
            assertEquals(
                    FileStorage.STORED,
                    h.stat(FileRef.id(10), Cancellation.NONE).join().file().storage());
        }
    }

    @Test
    void rangesClampBeforeFetchAndOwnTheirBytes() {
        var s = create().add(10, null, DATA, false, List.of());
        try (var h = open(s)) {
            var empty = h.readRange(FileRef.id(10), 999, 5, Cancellation.NONE).join();
            assertEquals(DATA.length, empty.offset());
            assertEquals(0, empty.length());
            assertEquals(0, s.objects.transfers());
            var r = h.readRange(FileRef.id(10), 3, 4, Cancellation.NONE).join();
            assertArrayEquals(java.util.Arrays.copyOfRange(DATA, 3, 7), r.bytes());
            byte[] changed = r.bytes();
            changed[0] = 0;
            assertArrayEquals(java.util.Arrays.copyOfRange(DATA, 3, 7), r.bytes());
        }
    }

    @Test
    void holesAreNamedClippedAndNeverZeroFilled() {
        var s = create().add(10, null, DATA, true, List.of(new FileHole(4, 6, "0000000000001234")));
        try (var h = open(s)) {
            var read = h.get(FileRef.id(10), Cancellation.NONE).join();
            assertEquals(FileReadState.PARTIAL, read.state());
            assertEquals(2, read.segments().size());
            assertEquals(List.of(new FileHole(4, 6, "0000000000001234")), read.holes());
            var clipped = h.readRange(FileRef.id(10), 6, 2, Cancellation.NONE).join();
            assertEquals(FileReadState.PROTECTED, clipped.state());
            assertEquals(List.of(new FileHole(6, 2, "0000000000001234")), clipped.holes());
        }
    }

    @Test
    void streamOutlivesHandleStopsAtHolesAndSeeks() {
        var s = create().add(10, null, DATA, true, List.of(new FileHole(4, 6, "0000000000001234")));
        var h = open(s);
        var got = h.openRead(FileRef.id(10), Cancellation.NONE).join();
        h.close();
        try (var stream = got.stream()) {
            assertEquals(FileReadState.PARTIAL, got.state());
            ByteBuffer bytes = ByteBuffer.allocate(100);
            assertEquals(4, stream.read(bytes, Cancellation.NONE).join());
            assertTrue(
                    assertThrows(
                                            CompletionException.class,
                                            () -> stream.read(bytes, Cancellation.NONE).join())
                                    .getCause()
                            instanceof FileHoleException);
            stream.seek(10);
            assertTrue(stream.read(bytes, Cancellation.NONE).join() > 0);
            stream.seek(999);
            assertEquals(-1, stream.read(bytes, Cancellation.NONE).join());
            assertEquals(List.of("0000000000001234"), stream.chunkKeys());
        }
    }

    @Test
    void listingIsAscendingPagedAndContinuesAfterHandleClose() {
        var s =
                create().add(30, null, DATA, true, List.of())
                        .add(2, null, DATA, false, List.of())
                        .noEncoding(11);
        var h = open(s);
        try (var cursor = h.list(FileFilter.ALL, Cancellation.NONE)) {
            var first = cursor.next(1, Cancellation.NONE).join();
            h.close();
            assertEquals(2, first.getFirst().fileDataId());
            var rest = cursor.next(10, Cancellation.NONE).join();
            assertEquals(
                    List.of(11L, 30L), rest.stream().map(FileTypes.BuildFile::fileDataId).toList());
            assertTrue(cursor.next(10, Cancellation.NONE).join().isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> h.stat(FileRef.id(2), Cancellation.NONE));
    }

    @Test
    void lookupFailuresAndNoEncodingRemainDistinct() {
        var s = create().add(1, null, DATA, true, List.of()).noEncoding(2);
        try (var h = open(s)) {
            assertEquals(
                    FileLookupState.NOT_IN_ENCODING,
                    h.stat(FileRef.id(2), Cancellation.NONE).join().state());
            assertTrue(h.exists(FileRef.id(2), Cancellation.NONE).join());
            assertEquals(
                    FileReadState.NOT_IN_ENCODING,
                    h.get(FileRef.id(2), Cancellation.NONE).join().state());
            assertEquals(
                    FileReadState.UNKNOWN_FILE,
                    h.get(FileRef.id(999), Cancellation.NONE).join().state());
        }
    }

    @Test
    void ensureDeduplicatesAndPublishesFinalProgressBeforeReturn() {
        var s =
                create().add(1, null, DATA, false, List.of())
                        .add(2, null, new byte[] {1, 2, 3}, true, List.of())
                        .noEncoding(3);
        try (var h = open(s)) {
            List<FileTypes.EnsureProgress> reports = new ArrayList<>();
            var result =
                    h.ensure(
                                    EnsureSelection.many(
                                            List.of(
                                                    FileRef.id(1),
                                                    FileRef.id(1),
                                                    FileRef.id(2),
                                                    FileRef.id(3),
                                                    FileRef.id(999))),
                                    EnsurePriority.BACKGROUND,
                                    reports::add,
                                    Cancellation.NONE)
                            .join();
            assertEquals(EnsureOutcome.INCOMPLETE, result.outcome());
            assertEquals(4, result.files().size());
            assertEquals(
                    List.of(
                            FileEnsureState.STORED,
                            FileEnsureState.ALREADY_STORED,
                            FileEnsureState.NOT_IN_ENCODING,
                            FileEnsureState.UNKNOWN_FILE),
                    result.files().stream().map(FileTypes.FileEnsured::state).toList());
            assertEquals(result.progress(), reports.getLast());
            assertTrue(result.progress().listed());
        }
    }

    @Test
    void precanceledEnsureReturnsCanceledWithoutFetch() {
        var s = create().add(1, null, DATA, false, List.of());
        try (var h = open(s)) {
            var token = new Cancellation();
            token.cancel();
            var result =
                    h.ensure(
                                    EnsureSelection.one(FileRef.id(1)),
                                    EnsurePriority.INTERACTIVE,
                                    p -> {
                                        throw new AssertionError("Observer isolated");
                                    },
                                    token)
                            .join();
            assertEquals(EnsureOutcome.CANCELED, result.outcome());
            assertEquals(FileEnsureState.CANCELED, result.files().getFirst().state());
            assertEquals(0, s.objects.transfers());
        }
    }
}
