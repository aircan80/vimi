package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class BudgetedTempFileTest {
    @TempDir Path directory;

    @Test
    void appendReservesBeforeWriteAndOldNewFilesCoexistUntilDelete() throws Exception {
        try (var budget = new TempBudget(1024, 40);
                var old =
                        BudgetedTempFile.create(
                                directory, "old", budget, "old-run", Cancellation.NONE);
                var next =
                        BudgetedTempFile.create(
                                directory, "next", budget, "replacement", Cancellation.NONE)) {
            old.appendFrame(new byte[16], Cancellation.NONE);
            assertEquals(20, budget.snapshot().disk());
            assertThrows(
                    TempBudget.Limit.class,
                    () -> next.appendFrame(new byte[17], Cancellation.NONE));
            assertEquals(0, Files.size(next.path()));
            assertEquals(20, budget.snapshot().disk());
            old.close();
            next.appendFrame(new byte[17], Cancellation.NONE);
            next.seal();
            next.transferTo("sealed-input");
            assertEquals(21, next.size());
            assertEquals(21, Files.size(next.path()));
        }
    }

    @Test
    void partialWriteRemainsChargedAndCannotContinueUntilDeletion() throws Exception {
        try (var budget = new TempBudget(1024, 100)) {
            var file =
                    BudgetedTempFile.create(
                            directory,
                            "partial",
                            budget,
                            "partial",
                            Cancellation.NONE,
                            (channel, header, body, token) -> {
                                header.limit(2);
                                channel.write(header);
                                throw new IOException("Authored partial-write failure");
                            });
            assertThrows(
                    IOException.class, () -> file.appendFrame(new byte[20], Cancellation.NONE));
            assertEquals(2, Files.size(file.path()));
            assertEquals(24, file.size());
            assertEquals(24, budget.snapshot().disk());
            assertThrows(IOException.class, () -> file.appendFrame(new byte[1], Cancellation.NONE));
            file.close();
            assertEquals(0, budget.snapshot().disk());
            assertTrue(Files.notExists(file.path()));
        }
    }

    @Test
    void canceledGrowthDoesNotCreateUnchargedBytesAndCloseIsIdempotent() throws Exception {
        try (var budget = new TempBudget(1024, 100)) {
            var file =
                    BudgetedTempFile.create(
                            directory, "cancel", budget, "cancel", Cancellation.NONE);
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> file.appendFrame(new byte[20], canceled));
            assertEquals(0, Files.size(file.path()));
            assertEquals(0, budget.snapshot().disk());
            file.close();
            file.close();
            assertEquals(0, budget.snapshot().reservations());
        }
    }
}
