package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ValidationTest {
    @TempDir Path folder;

    @Test
    void fullVerifyAndReplayApplyIdentityChecksBeyondIntegrity() {
        ObjectStore store = ObjectStore.open(folder).join();
        byte[] bytes = {1, 2, 3};
        ObjectKey badNamespace = new ObjectKey(1, 1, Format.hash("MD5", bytes));
        try {
            // Simulate an entry from an older writer that checked bytes but missed namespace
            // validation.
            store.write(
                            () -> {
                                store.append(
                                        badNamespace,
                                        bytes.length,
                                        Origin.CDN,
                                        false,
                                        bytes,
                                        null,
                                        null);
                                return null;
                            })
                    .join();
            assertEquals(ReadState.PRESENT, store.read(badNamespace, NONE).join().state());
            assertEquals(0, store.verify(false, NONE).join().corruptCount());
            assertEquals(1, store.verify(true, NONE).join().corruptCount());
            assertEquals(1, store.scan(NONE).join().unverifiable());
            assertFalse(store.has(badNamespace, NONE).join());
            assertEquals(
                    1,
                    store.scan(NONE).join().unverifiable()); // a second rebuild must not admit it
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void tornAndNewerHeadersProduceDifferentReadAndScanOutcomes() throws Exception {
        ObjectStore store = ObjectStore.open(folder).join();
        byte[] bytes = {4, 5, 6};
        ObjectKey key = key(bytes);
        try {
            store.put(List.of(new PutObject(key, bytes)), true, NONE).join();
            StoreIndex.Row row = store.index.find(key);
            Path pack = store.packPath(row.pack());
            try (FileChannel file =
                    FileChannel.open(pack, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                byte[] header = Format.read(file, row.offset(), 96);
                header[74] = 1;
                Format.le(header).putInt(92, (int) Format.crc(header, 0, 92));
                Format.write(file, row.offset(), header);
                file.force(true);
            }
            byte[] before = Files.readAllBytes(pack);
            long digest = store.index.objectDigest;
            assertEquals(ReadState.CORRUPT, store.read(key, NONE).join().state());
            failure(store.scan(NONE), "OBJECT_STORE_NEWER_FORMAT");
            assertArrayEquals(before, Files.readAllBytes(pack));
            assertEquals(digest, store.index.objectDigest);
        } finally {
            store.closeAsync().join();
        }
        byte[] index = Files.readAllBytes(folder.resolve("objects.sqlite3"));
        // Without an index, open must preflight the newer entry before replacing anything.
        Files.write(folder.resolve("objects.sqlite3"), new byte[] {0, 1, 2});
        failure(ObjectStore.open(folder), "OBJECT_STORE_NEWER_FORMAT");
        assertArrayEquals(
                new byte[] {0, 1, 2}, Files.readAllBytes(folder.resolve("objects.sqlite3")));
        Files.write(folder.resolve("objects.sqlite3"), index);
    }

    @Test
    void readRejectsDamagedIndexRowsAndDistinguishesLostFiles() throws Exception {
        ObjectStore store = ObjectStore.open(folder).join();
        byte[] bytes = {4, 5, 6};
        ObjectKey key = key(bytes);
        try {
            store.put(List.of(new PutObject(key, bytes)), true, NONE).join();
            store.write(
                            () -> {
                                store.database.exec(
                                        "UPDATE objects SET length=-1 WHERE k=?", key.indexBytes());
                                return null;
                            })
                    .join();
            assertEquals(ReadState.CORRUPT, store.read(key, NONE).join().state());
            store.scan(NONE).join();
            StoreIndex.Row row = store.index.find(key);
            Files.delete(store.packPath(row.pack()));
            assertEquals(ReadState.LOST, store.read(key, NONE).join().state());
            assertEquals(1, store.verify(true, NONE).join().lostCount());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void acceptsMaximumGeneratedPayloadAndRejectsOverLimitDeclarations() {
        ObjectStore store =
                ObjectStore.open(folder, new Options(Kind.GENERATED, 2L << 30, Clock.systemUTC()))
                        .join();
        try {
            ObjectKey key = new ObjectKey(7, new byte[16]);
            byte[] bytes = new byte[Format.WHOLE_LIMIT];
            bytes[bytes.length - 1] = 42;
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, bytes)), true, NONE)
                            .join()
                            .getFirst()
                            .outcome());
            assertArrayEquals(
                    new byte[] {42},
                    store.readRange(key, bytes.length - 1, 1, NONE).join().bytes());
            assertEquals(0, store.verify(true, NONE).join().corruptCount());
            assertEquals(
                    "OBJECT_TOO_LARGE",
                    store.put(
                                    List.of(
                                            new PutObject(
                                                    key,
                                                    bytes.length + 1L,
                                                    new byte[1],
                                                    Origin.CDN)),
                                    true,
                                    NONE)
                            .join()
                            .getFirst()
                            .code());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void streamsEncodedPayloadAboveWholeObjectLimit() {
        byte[] chunk = new byte[Format.WHOLE_LIMIT + 1];
        chunk[0] = 'E';
        chunk[chunk.length - 1] = 42;
        EncodedFixture fixture = EncodedFixture.chunks(chunk);
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertEquals(
                    "OBJECT_TOO_LARGE",
                    store.put(List.of(new PutObject(fixture.key(), fixture.bytes())), true, NONE)
                            .join()
                            .getFirst()
                            .code());
            ObjectStaging stage =
                    store.stage(fixture.key(), fixture.bytes().length, Origin.CDN, NONE).join();
            for (int at = 0; at < fixture.bytes().length; at += 1 << 20) {
                stage.append(
                                Arrays.copyOfRange(
                                        fixture.bytes(),
                                        at,
                                        Math.min(at + (1 << 20), fixture.bytes().length)),
                                NONE)
                        .join();
            }
            assertEquals(PutOutcome.STORED, stage.commit(NONE).join().outcome());
            failure(store.read(fixture.key(), NONE), "OBJECT_STORE_LIMIT");
            assertArrayEquals(
                    new byte[] {42},
                    store.readRange(fixture.key(), fixture.bytes().length - 1, 1, NONE)
                            .join()
                            .bytes());
            assertEquals(0, store.verify(true, NONE).join().corruptCount());
        } finally {
            store.closeAsync().join();
        }
    }
}
