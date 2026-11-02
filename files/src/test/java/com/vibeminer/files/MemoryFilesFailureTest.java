package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class MemoryFilesFailureTest {
    @Test
    void typedFailuresAndKnownEncryptionKeysAreScriptableWithoutRealDecoder() {
        var s = new FilesScenario(true).add(1, null, new byte[] {1, 2}, true, List.of());
        var fake = (MemoryFiles) s.files;
        fake.chunkKeys(FilesScenario.REF, "enUS", 1, List.of("0000000000000123"));
        try (var h = fake.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
                var stream = h.openRead(FileRef.id(1), Cancellation.NONE).join().stream()) {
            assertEquals(List.of("0000000000000123"), stream.chunkKeys());
            Instant at = Instant.parse("2020-01-01T00:00:00Z");
            fake.failure(
                    FilesScenario.REF,
                    "enUS",
                    1,
                    FileRead.failed(FileReadState.UNAVAILABLE, 0, "TEST_OFFLINE", "why", at));
            assertEquals(at, h.get(FileRef.id(1), Cancellation.NONE).join().observedAt());
            var e =
                    (FileReadException)
                            assertThrows(
                                            CompletionException.class,
                                            () ->
                                                    stream.read(
                                                                    ByteBuffer.allocate(2),
                                                                    Cancellation.NONE)
                                                            .join())
                                    .getCause();
            assertEquals("TEST_OFFLINE", e.code());
            assertEquals(at, e.observedAt());
            fake.failure(FilesScenario.REF, "enUS", 1, null);
            assertEquals(2, stream.read(ByteBuffer.allocate(2), Cancellation.NONE).join());
        }
    }
}
