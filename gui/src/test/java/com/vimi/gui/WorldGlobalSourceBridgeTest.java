package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.AuthoredGlobalSource;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.rendering.bgfx.GpuGlobalWmoSceneFile;
import com.vibeminer.rendering.bgfx.WorldGlobalHashes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Genuine native Map -> actual global Source -> fixed canonical file, no child/FX. */
public final class WorldGlobalSourceBridgeTest {
    private WorldGlobalSourceBridgeTest() {}

    public static void main(String[] args) throws Exception {
        sourceAndMovedFileRetireIndependently();
        unadvertisedCloseAndLateCancellationRemovePrivateFiles();
        finalSourceChangeRemovesUnadvertisedFile();
        canceledActualReadDrainsBeforeFailure();
        createNewFailurePreservesUnownedFile();
        thirdBridgeRefusesBeforeSourceIoAndRemovesOnlyItsDirectory();
    }

    private static void sourceAndMovedFileRetireIndependently() throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-");
        try (var fixture = new AuthoredGlobalSource()) {
            var prepared =
                    WorldGlobalSourceBridge.prepare(
                            fixture.source(), fixture.scope(), parent, Cancellation.NONE);
            var expected = prepared.expected();
            var input = prepared.move();
            Path path = input.input().path(), directory = path.getParent();
            try {
                prepared.close();
                check(Files.exists(path), "Source must not delete moved file");
                fixture.closeAsync().get(3, TimeUnit.SECONDS);
                check(Files.exists(path), "Service close must not delete moved file");
                try (var read = GpuGlobalWmoSceneFile.read(path, expected.input(), () -> false)) {
                    check(
                            WorldGlobalHashes.mesh(read, () -> false)
                                    .nativeWire()
                                    .equals(expected.nativeWireSha256()),
                            "actual native wire hash");
                    check(
                            read.counts().vertices() == 3 && read.counts().images() == 1,
                            "actual qualified counts");
                }
            } finally {
                input.close();
                prepared.close();
            }
            check(
                    !Files.exists(path) && !Files.exists(directory),
                    "moved input removes its request directory");
            check(fixture.payloadCredits() == 0, "Source payload drained");
        } finally {
            Files.delete(parent);
        }
    }

    private static void unadvertisedCloseAndLateCancellationRemovePrivateFiles() throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-unadvertised-");
        try (var fixture = new AuthoredGlobalSource()) {
            var prepared =
                    WorldGlobalSourceBridge.prepare(
                            fixture.source(), fixture.scope(), parent, Cancellation.NONE);
            prepared.close();
            check(empty(parent), "unadvertised source closes file/directory");
            var cancellation = new Cancellation();
            prepared =
                    WorldGlobalSourceBridge.prepare(
                            fixture.source(), fixture.scope(), parent, cancellation);
            cancellation.cancel();
            boolean failed = false;
            try {
                prepared.move();
            } catch (java.util.concurrent.CancellationException expected) {
                failed = true;
            } finally {
                prepared.close();
            }
            check(failed && empty(parent), "late cancellation cannot move a live file");
        } finally {
            Files.delete(parent);
        }
    }

    private static void finalSourceChangeRemovesUnadvertisedFile() throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-stale-");
        try (var fixture = new AuthoredGlobalSource()) {
            fixture.onStat(
                    id -> {
                        try (var paths = Files.walk(parent)) {
                            if (paths.anyMatch(
                                    p -> p.getFileName().toString().equals("global.wgs"))) {
                                fixture.markChanged(AuthoredGlobalSource.IMAGE);
                            }
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    });
            boolean failed = false;
            try {
                WorldGlobalSourceBridge.prepare(
                        fixture.source(), fixture.scope(), parent, Cancellation.NONE);
            } catch (WorldTerrainStreaming.Refusal expected) {
                check(
                        expected.reason()
                                == WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED,
                        "actual final binding source invalidation");
                failed = true;
            }
            check(failed && empty(parent), "final binding change closes actual unadvertised file");
            check(fixture.payloadCredits() == 0, "failed source output retires payload");
        } finally {
            Files.delete(parent);
        }
    }

    private static void canceledActualReadDrainsBeforeFailure() throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-cancel-");
        try (var fixture = new AuthoredGlobalSource()) {
            var gate = fixture.holdNextRead(AuthoredGlobalSource.IMAGE);
            var cancel = new Cancellation();
            var result =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    WorldGlobalSourceBridge.prepare(
                                            fixture.source(), fixture.scope(), parent, cancel);
                                    throw new AssertionError("canceled source returned");
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            });
            try {
                gate.entered().get(3, TimeUnit.SECONDS);
                cancel.cancel();
                gate.canceled().get(3, TimeUnit.SECONDS);
                check(!result.isDone(), "actual lower read must drain before producer terminal");
            } finally {
                gate.release();
            }
            try {
                result.get(3, TimeUnit.SECONDS);
                throw new AssertionError("canceled operation succeeded");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(
                        expected.getCause() instanceof java.util.concurrent.CancellationException,
                        "cancellation must remain clean: "
                                + expected.getCause().getClass().getName());
            } catch (java.util.concurrent.CancellationException expected) {
            }
            check(
                    empty(parent) && fixture.payloadCredits() == 0,
                    "cancel retires all private files/payloads");
        } finally {
            Files.delete(parent);
        }
    }

    private static void createNewFailurePreservesUnownedFile() throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-create-new-");
        try (var fixture = new AuthoredGlobalSource()) {
            var gate = fixture.holdNextRead(AuthoredGlobalSource.IMAGE);
            var result =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    WorldGlobalSourceBridge.prepare(
                                            fixture.source(),
                                            fixture.scope(),
                                            parent,
                                            Cancellation.NONE);
                                    throw new AssertionError("preexisting output accepted");
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            });
            Path directory = null, path = null;
            try {
                gate.entered().get(3, TimeUnit.SECONDS);
                try (var paths = Files.list(parent)) {
                    directory = paths.findFirst().orElseThrow();
                }
                path = directory.resolve("global.wgs");
                Files.writeString(path, "preexisting unowned file");
            } finally {
                gate.release();
            }
            try {
                result.get(3, TimeUnit.SECONDS);
                throw new AssertionError("CREATE_NEW unexpectedly succeeded");
            } catch (java.util.concurrent.ExecutionException expected) {
            }
            check(
                    Files.readString(path).equals("preexisting unowned file"),
                    "CREATE_NEW failure preserves unowned bytes");
            Files.delete(path);
            Files.delete(directory);
            check(fixture.payloadCredits() == 0, "output failure retires source payload");
        } finally {
            Files.delete(parent);
        }
    }

    private static void thirdBridgeRefusesBeforeSourceIoAndRemovesOnlyItsDirectory()
            throws Exception {
        Path parent = Files.createTempDirectory("global-bridge-pressure-");
        try (var fixture = new AuthoredGlobalSource()) {
            try (var first =
                            WorldGlobalSourceBridge.prepare(
                                    fixture.source(), fixture.scope(), parent, Cancellation.NONE);
                    var second =
                            WorldGlobalSourceBridge.prepare(
                                    fixture.source(), fixture.scope(), parent, Cancellation.NONE)) {
                check(first.isOpen() && second.isOpen(), "actual two Source holders");
                int before = fixture.opened(AuthoredGlobalSource.ROOT);
                boolean refused = false;
                try {
                    WorldGlobalSourceBridge.prepare(
                            fixture.source(), fixture.scope(), parent, Cancellation.NONE);
                } catch (WorldTerrainStreaming.Refusal expected) {
                    refused = expected.reason() == WorldTerrainStreaming.Refusal.Reason.RETRY_LATER;
                }
                check(
                        refused && fixture.opened(AuthoredGlobalSource.ROOT) == before,
                        "third bridge owns no source IO credit");
                try (var dirs = Files.list(parent)) {
                    check(dirs.count() == 2, "refused bridge removes only its own empty directory");
                }
            }
            check(
                    empty(parent) && fixture.payloadCredits() == 0,
                    "both unadvertised outputs retire");
        } finally {
            Files.delete(parent);
        }
    }

    private static boolean empty(Path path) throws Exception {
        try (var paths = Files.list(path)) {
            return paths.findAny().isEmpty();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
