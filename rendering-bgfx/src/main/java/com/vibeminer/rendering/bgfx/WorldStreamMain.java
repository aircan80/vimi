package com.vibeminer.rendering.bgfx;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Explicit child-only World v2 dispatch. Never emits the legacy viewer's status protocol. */
final class WorldStreamMain {
    private WorldStreamMain() {}

    static boolean requested(String[] args) {
        for (int i = 0; i < args.length; i += 2) {
            if (args[i].equals("--world-session-v2")) {
                return true;
            }
        }
        return false;
    }

    /** {@code presentParent}: #381 spike, the GUI window to present into (0: readback). */
    record Options(
            Path directory,
            Path frames,
            String sha256,
            long parent,
            int backend,
            long presentParent) {}

    static Options options(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length
                    || !Set.of(
                                    "--world-session-v2",
                                    "--session-sha256",
                                    "--frames",
                                    "--parent-pid",
                                    "--backend",
                                    "--present-parent")
                            .contains(args[i])
                    || values.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Invalid World v2 arguments");
            }
        }
        if (!values.keySet()
                .containsAll(
                        Set.of(
                                "--world-session-v2",
                                "--session-sha256",
                                "--frames",
                                "--parent-pid"))) {
            throw new IllegalArgumentException(
                    "World v2 requires session, checksum, frames and parent");
        }
        var directory = Path.of(values.get("--world-session-v2"));
        var frames = Path.of(values.get("--frames"));
        String sha = values.get("--session-sha256"), parentText = values.get("--parent-pid");
        if (!directory.isAbsolute()
                || !directory.equals(directory.normalize())
                || !frames.isAbsolute()
                || !frames.equals(frames.normalize())
                || !directory.equals(frames.getParent())
                || frames.getFileName().toString().equals("session.wss")
                || !sha.matches("[0-9a-f]{64}")
                || !parentText.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid World v2 session paths or identity");
        }
        long parent = Long.parseLong(parentText);
        String present = values.get("--present-parent");
        if (present != null && !present.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid World v2 present parent");
        }
        return new Options(
                directory,
                frames,
                sha,
                parent,
                GpuViewerMain.backend(values.get("--backend")),
                present == null ? 0 : Long.parseLong(present));
    }

    static int execute(String[] args) {
        var finished = new AtomicBoolean();
        try {
            var options = options(args);
            var parent =
                    ProcessHandle.of(options.parent())
                            .filter(ProcessHandle::isAlive)
                            .orElseThrow(() -> new IOException("World v2 parent is unavailable"));
            // Armed before bootstrap IO/mapping/native initialization, without consuming stdin.
            Thread.ofVirtual()
                    .name("world-v2-bootstrap-parent-watchdog")
                    .start(
                            () -> {
                                while (!finished.get() && parent.isAlive()) {
                                    LockSupport.parkNanos(20_000_000L);
                                }
                                if (!finished.get()) {
                                    long until =
                                            System.nanoTime() + WorldStatusDeadline.TIMEOUT_NANOS;
                                    while (!finished.get() && System.nanoTime() - until < 0) {
                                        LockSupport.parkNanos(20_000_000L);
                                    }
                                    if (!finished.get()) {
                                        Runtime.getRuntime().halt(2);
                                    }
                                }
                            });
            privateDirectory(options.directory());
            var session =
                    WorldStreamEnvelope.decodeSession(
                            readSession(options.directory().resolve("session.wss")),
                            options.sha256());
            if (!Files.isRegularFile(options.frames(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World v2 frames are not a regular private file");
            }
            try (var frames =
                    WorldFrameFileV2.open(options.frames(), session.nonce(), session.scope())) {
                // #381: a present parent selects native presentation into a child window. The
                // owner closes it; this closes it too if anything fails first (close is
                // idempotent).
                try (var window =
                        options.presentParent() == 0
                                ? null
                                : new WorldWindowPresentation(
                                        WorldChildWindow.create(options.presentParent()))) {
                    boolean success =
                            WorldStreamSession.run(
                                    session,
                                    options.directory(),
                                    System.in,
                                    new FileOutputStream(FileDescriptor.out),
                                    frames,
                                    options.backend(),
                                    options.parent(),
                                    () -> Runtime.getRuntime().halt(2),
                                    window);
                    return success ? 0 : 1;
                }
            }
        } catch (Exception | LinkageError failure) {
            String message =
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage();
            message = message.replaceAll("[\\p{Cntrl}]", " ");
            System.err.println(
                    "World v2 failed: " + message.substring(0, Math.min(220, message.length())));
            return 1;
        } finally {
            finished.set(true);
        }
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isDirectory()) {
            throw new IOException("World v2 directory is not a regular owned directory");
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            for (var permission :
                    Set.of(
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_WRITE,
                            PosixFilePermission.OTHERS_EXECUTE)) {
                if (permissions.contains(permission)) {
                    throw new IOException("World v2 requires a private directory");
                }
            }
            var owner =
                    directory
                            .getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
            if (!Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
                throw new IOException("World v2 directory is not owned by this user");
            }
        }
    }

    private static byte[] readSession(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World v2 bootstrap is not a regular private file");
        }
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != WorldStreamEnvelope.BYTES) {
                throw new IOException("Invalid World v2 bootstrap length");
            }
            byte[] bytes = new byte[WorldStreamEnvelope.BYTES];
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Truncated World v2 bootstrap");
                }
            }
            if (channel.size() != bytes.length || channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("Changed World v2 bootstrap");
            }
            return bytes;
        }
    }
}
