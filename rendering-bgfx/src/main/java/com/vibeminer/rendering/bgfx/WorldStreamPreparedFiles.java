package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Fresh parent-owned bootstrap/mapping. Transfer precedes any asynchronous child start. */
final class WorldStreamPreparedFiles {
    private enum State {
        PREPARED,
        TRANSFERRED,
        CLOSING
    }

    final class LaunchFiles {
        private LaunchFiles() {}

        Path directory() {
            return directory;
        }

        Path framePath() {
            return directory.resolve("frames.wsf");
        }

        WorldFrameFileV2 frames() {
            return mapping;
        }

        List<String> arguments() {
            return arguments;
        }

        /** Caller must first prove child exit and local mapping/file-worker drain. */
        CompletableFuture<Void> closeAsync() {
            return cleanup();
        }
    }

    private final Object lock = new Object();
    private final Path directory;
    private final WorldFrameFileV2 mapping;
    private final List<String> arguments;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private State state = State.PREPARED;
    private boolean cleanupStarted;

    private WorldStreamPreparedFiles(
            Path directory, WorldFrameFileV2 mapping, List<String> arguments) {
        this.directory = directory;
        this.mapping = mapping;
        this.arguments = List.copyOf(arguments);
    }

    /** Bounded filesystem work; invoked off caller/FX by the future public preparation facade. */
    static WorldStreamPreparedFiles create(
            Path parent, WorldStreamEnvelope.Session session, String backend) throws IOException {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(session);
        if (backend != null) {
            GpuViewerMain.backend(backend); // Reject unknown names before creating owners.
        }
        Path directory =
                Files.createTempDirectory(parent.toAbsolutePath().normalize(), "world-v2-");
        Path bootstrap = directory.resolve("session.wss"), frames = directory.resolve("frames.wsf");
        WorldFrameFileV2 mapping = null;
        try {
            byte[] bytes = WorldStreamEnvelope.encode(session);
            try (var channel =
                    FileChannel.open(
                            bootstrap,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            mapping = WorldFrameFileV2.create(frames, session.nonce(), session.scope());
            var arguments =
                    new ArrayList<>(
                            List.of(
                                    "--world-session-v2",
                                    directory.toString(),
                                    "--session-sha256",
                                    sha(bytes),
                                    "--frames",
                                    frames.toString(),
                                    "--parent-pid",
                                    Long.toString(ProcessHandle.current().pid())));
            if (backend != null) {
                arguments.add("--backend");
                arguments.add(backend);
            }
            return new WorldStreamPreparedFiles(directory, mapping, arguments);
        } catch (IOException | RuntimeException | Error failure) {
            if (mapping != null) {
                try {
                    mapping.close();
                } catch (IOException | RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            for (Path owned : List.of(frames, bootstrap, directory)) {
                try {
                    Files.deleteIfExists(owned);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /** Irreversible capability transfer; the preparation handle can no longer delete files. */
    LaunchFiles claimForLaunch() {
        synchronized (lock) {
            if (state != State.PREPARED) {
                throw new IllegalStateException("World files are already transferred or closing");
            }
            state = State.TRANSFERRED;
            return new LaunchFiles();
        }
    }

    CompletableFuture<Void> closePreparationAsync() {
        synchronized (lock) {
            if (state == State.TRANSFERRED) {
                return CompletableFuture.completedFuture(null);
            }
            state = State.CLOSING;
        }
        return cleanup();
    }

    private CompletableFuture<Void> cleanup() {
        synchronized (lock) {
            if (cleanupStarted) {
                return drained.copy();
            }
            cleanupStarted = true;
        }
        Thread.ofVirtual()
                .name("world-v2-files-cleanup")
                .start(
                        () -> {
                            try {
                                mapping.close();
                                // Only known owned files: never recursively remove unregistered
                                // directory contents.
                                Files.deleteIfExists(directory.resolve("frames.wsf"));
                                Files.deleteIfExists(directory.resolve("session.wss"));
                                Files.delete(directory);
                                drained.complete(null);
                            } catch (IOException | RuntimeException error) {
                                drained.completeExceptionally(error);
                            }
                        });
        return drained.copy();
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
