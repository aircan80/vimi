package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DerivedDatabaseTest {
    @TempDir Path directory;

    @Test
    void transactionsPersistAndScopedSessionsCannotEscape() {
        Path path = directory.resolve("queries.sqlite");
        var escaped = new AtomicReference<DerivedDatabase.Session>();
        try (var database = DerivedDatabase.open(path, Cancellation.NONE).join()) {
            database.transaction(
                            session -> {
                                session.execute(
                                        "CREATE TABLE values_test(id INTEGER PRIMARY KEY,label TEXT)");
                                session.execute(
                                        "INSERT INTO values_test VALUES(?,?)",
                                        1L,
                                        "literal ' OR 1=1");
                                escaped.set(session);
                                return null;
                            },
                            Cancellation.NONE)
                    .join();
            assertThrows(
                    IllegalStateException.class,
                    () -> escaped.get().query("SELECT * FROM values_test"));
            assertThrows(
                    CompletionException.class,
                    () ->
                            database.transaction(
                                            session -> {
                                                session.execute(
                                                        "INSERT INTO values_test VALUES(2,'rollback')");
                                                throw new IllegalStateException("fixture");
                                            },
                                            Cancellation.NONE)
                                    .join());
        }
        try (var reopened = DerivedDatabase.open(path, Cancellation.NONE).join()) {
            assertEquals(
                    1L,
                    reopened.read(
                                    session ->
                                            session.query("SELECT count(*) FROM values_test")
                                                    .getFirst()[0],
                                    Cancellation.NONE)
                            .join());
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    CompletionException.class,
                    () -> reopened.read(session -> session.query("SELECT 1"), token).join());
        }
    }
}
