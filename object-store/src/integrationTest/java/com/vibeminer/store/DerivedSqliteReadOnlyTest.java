package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DerivedSqliteReadOnlyTest {
    @TempDir Path directory;

    @Test
    void readOnlySessionsSupportSnapshotsAndRejectWritesWithoutChangingSource() throws Exception {
        Path path = directory.resolve("source.sqlite");
        try (var writer = new DerivedSqliteConnection(path)) {
            writer.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("CREATE TABLE records(value INTEGER NOT NULL)");
                        session.execute("INSERT INTO records VALUES(7)");
                        return null;
                    });
        }
        byte[] before = Files.readAllBytes(path);
        try (var reader = DerivedSqliteConnection.openReadOnly(path)) {
            long value =
                    reader.run(
                            Cancellation.NONE,
                            true,
                            session ->
                                    (Long)
                                            session.query("SELECT value FROM records")
                                                    .getFirst()[0]);
            assertEquals(7, value);
            assertThrows(
                    NativeSqlite.Failure.class,
                    () ->
                            reader.run(
                                    Cancellation.NONE,
                                    false,
                                    session -> {
                                        session.execute("INSERT INTO records VALUES(8)");
                                        return null;
                                    }));
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }
}
