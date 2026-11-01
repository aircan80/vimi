package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class MalformedIndexTest {
    @TempDir Path folder;
    ObjectStore store;

    @BeforeEach
    void open() {
        CrashFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
    }

    @AfterEach
    void close() {
        store.closeAsync().join();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sha256=NULL",
                "sha256=x'00'",
                "sha256='invalid'",
                "pack='invalid'",
                "pack=2147483648",
                "length=-1",
                "offset=0"
            })
    void badMetadataReturnsCorruptAndScrubRepairsIt(String damage) {
        ObjectKey key = key(CrashFixture.baseline());
        store.write(
                        () -> {
                            store.database.transaction(
                                    true,
                                    () -> {
                                        // Simulate damaged schema/rows, including nulls the normal
                                        // schema prevents.
                                        store.database.exec(
                                                "ALTER TABLE objects RENAME TO saved_objects");
                                        store.database.exec(
                                                "CREATE TABLE objects(k BLOB PRIMARY KEY,pack INTEGER,offset INTEGER,length INTEGER,sha256 BLOB,last_read INTEGER) WITHOUT ROWID");
                                        store.database.exec(
                                                "INSERT INTO objects SELECT * FROM saved_objects");
                                        store.database.exec("DROP TABLE saved_objects");
                                        store.database.exec(
                                                "UPDATE objects SET " + damage + " WHERE k=?",
                                                key.indexBytes());
                                    });
                            return null;
                        })
                .join();
        assertEquals(ReadState.CORRUPT, store.read(key, NONE).join().state());
        assertEquals(ReadState.CORRUPT, store.readRange(key, 0, 1, NONE).join().state());
        var verified = store.verify(true, NONE).join();
        assertEquals(1, verified.corruptCount());
        assertEquals(List.of(key), verified.corrupt());
        failure(store.delete(List.of(key), NONE), "OBJECT_STORE_INDEX_UNREADABLE");
        var scrub = store.scrub(true, NONE).join();
        assertTrue(scrub.indexRebuilt());
        assertEquals(0, scrub.verify().corruptCount());
        assertArrayEquals(CrashFixture.baseline(), store.read(key, NONE).join().bytes());
        assertNull(store.scrub(true, NONE).join().indexProblem());
    }

    @Test
    void invalidCursorKeyReportsIndexFailureAndCanBeScrubbed() {
        store.write(
                        () -> {
                            store.database.exec(
                                    "UPDATE objects SET k=? WHERE k=?",
                                    new byte[17],
                                    key(CrashFixture.baseline()).indexBytes());
                            return null;
                        })
                .join();
        failure(store.verify(true, NONE), "OBJECT_STORE_INDEX_UNREADABLE");
        assertTrue(store.scrub(true, NONE).join().indexRebuilt());
        assertArrayEquals(
                CrashFixture.baseline(),
                store.read(key(CrashFixture.baseline()), NONE).join().bytes());
    }
}
