package com.vibeminer.questie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.Cancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestieRepositoryTest {
    @TempDir Path directory;

    @Test
    void opensOfflineAndSeparatesOriginalAndDerivedLifecycles() {
        var path = directory.resolve("questie");
        var repository = QuestieRepository.open(path, Cancellation.NONE).join();
        assertTrue(repository.releases(Cancellation.NONE).join().isEmpty());
        assertTrue(repository.sourceSnapshots(Cancellation.NONE).join().isEmpty());
        assertTrue(repository.versions(Cancellation.NONE).join().isEmpty());
        assertTrue(Files.isDirectory(path.resolve("sources")));
        assertThrows(
                CompletionException.class,
                () -> repository.prepare("0".repeat(64), ignored -> {}, Cancellation.NONE).join());
        repository.closeAsync().join();
        repository.closeAsync().join();
        assertThrows(
                CompletionException.class, () -> repository.versions(Cancellation.NONE).join());
        try (var reopened = QuestieRepository.open(path, Cancellation.NONE).join()) {
            assertEquals(0, reopened.versions(Cancellation.NONE).join().size());
        }
    }

    @Test
    void canceledOpenDoesNotCreateAStore() {
        var token = new Cancellation();
        token.cancel();
        var path = directory.resolve("cancelled");
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> QuestieRepository.open(path, token).join());
        assertFalse(Files.exists(path));
    }
}
