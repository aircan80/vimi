package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStreamPreparedFilesTest {
    @TempDir Path directory;
    private static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(new WorldStreamState.Nonce(10, 20), "ab".repeat(32), 1);

    @Test
    void realBootstrapAndMappingHaveExactFreshIdentityAndImmutableLaunchArguments()
            throws Exception {
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, "vulkan");
        var files = prepared.claimForLaunch();
        var args = files.arguments().toArray(String[]::new);
        var options = WorldStreamMain.options(args);
        assertEquals(files.directory(), options.directory());
        assertEquals(files.framePath(), options.frames());
        assertEquals(256, Files.size(files.directory().resolve("session.wss")));
        assertEquals(WorldFrameFileV2.FILE_BYTES, Files.size(files.framePath()));
        var decoded =
                WorldStreamEnvelope.decodeSession(
                        Files.readAllBytes(files.directory().resolve("session.wss")),
                        options.sha256());
        assertEquals(SESSION, decoded);
        assertTrue(files.frames().freshFor(SESSION));
        assertThrows(UnsupportedOperationException.class, () -> files.arguments().clear());
        files.closeAsync().get(3, TimeUnit.SECONDS);
        assertFalse(Files.exists(files.directory()));
    }

    @Test
    void closingOldPreparationCannotDeleteFilesDuringHeldAsynchronousStartAfterTransfer()
            throws Exception {
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var files = prepared.claimForLaunch(); // Must happen BEFORE scheduling Starter.
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CompletableFuture<Void>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                                assertNotNull(
                                        WorldStreamEnvelope.decodeSession(
                                                Files.readAllBytes(
                                                        files.directory().resolve("session.wss")),
                                                WorldStreamMain.options(
                                                                files.arguments()
                                                                        .toArray(String[]::new))
                                                        .sha256()));
                                started.complete(null);
                            } catch (Throwable error) {
                                started.completeExceptionally(error);
                            }
                        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        prepared.closePreparationAsync().get(1, TimeUnit.SECONDS);
        assertTrue(Files.exists(files.framePath()));
        assertTrue(files.frames().freshFor(SESSION));
        assertThrows(IllegalStateException.class, prepared::claimForLaunch);
        release.countDown();
        started.get(3, TimeUnit.SECONDS);
        files.closeAsync().get(3, TimeUnit.SECONDS);
        assertFalse(Files.exists(files.directory()));
    }

    @Test
    void neverLaunchedCloseRejectsTransferAndCleanupFailureCannotBecomeSuccessOnRetry()
            throws Exception {
        var never = WorldStreamPreparedFiles.create(directory, SESSION, "vulkan");
        never.closePreparationAsync().get(3, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, never::claimForLaunch);
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, "vulkan");
        var files = prepared.claimForLaunch();
        Path unregistered = files.directory().resolve("not-owned-by-transport");
        Files.writeString(unregistered, "authored foreign file");
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> files.closeAsync().get(3, TimeUnit.SECONDS));
        assertTrue(Files.exists(unregistered));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> files.closeAsync().get(3, TimeUnit.SECONDS));
        Files.delete(unregistered);
        Files.delete(files.directory());
    }
}
