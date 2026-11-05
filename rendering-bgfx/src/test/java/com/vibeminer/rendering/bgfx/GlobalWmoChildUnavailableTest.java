package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The child's classified capability negative, without a GPU: a fake context refuses. */
class GlobalWmoChildUnavailableTest {
    @TempDir Path directory;

    private static final GlobalWmoAuthoredData AUTHORED =
            new GlobalWmoAuthoredData(
                    new float[] {
                        10, -10, -10, 0, 0, 10, 10, -10, 1, 0, 10, -10, 10, 0, 1, 10, 10, 10, 1, 1
                    },
                    new int[] {0, 1, 2, 2, 1, 3},
                    List.of(
                            new byte[] {
                                (byte) 255,
                                0,
                                0,
                                (byte) 255,
                                0,
                                (byte) 255,
                                0,
                                (byte) 255,
                                0,
                                0,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255
                            }),
                    List.of(new WorldGlobalHashes.Batch(0, 0, 0, 6)));

    @Test
    void missingBackendCapabilityReportsUnavailableThenRetiresAndCloses() throws Exception {
        Path inputDir =
                Files.createDirectories(directory.resolve("input").resolve("global-source-1"));
        var destination =
                WorldGlobalSceneWriter.Destination.create(inputDir, new Object(), "7".repeat(64));
        try (var input =
                WorldGlobalSceneWriter.writeUnadvertised(destination, AUTHORED, () -> false)
                        .move()) {
            Path framesPath =
                    Files.createDirectory(directory.resolve("global-frames-1"))
                            .resolve("frames.vgpu");
            long frameNonce;
            try (var frames = GpuFrameFile.create(framesPath, 800, 500)) {
                frameNonce = frames.sessionNonce();
                var launch =
                        new GlobalWmoLaunch(
                                input.path(),
                                input.expected().input(),
                                framesPath,
                                frameNonce,
                                ProcessHandle.current().pid(),
                                GlobalWmoLaunch.Backend.DEFAULT);
                var identity = input.expected().identity();
                var out = new ByteArrayOutputStream();
                int[] shutdowns = {0};
                try (var statuses = new GlobalWmoMain.StatusWriter(out)) {
                    var control = new GlobalWmoMain.Control(identity.nonce());
                    GlobalWmoMain.run(
                            launch,
                            control,
                            statuses,
                            new GlobalWmoMain.Context() {
                                @Override
                                public void initialize() throws GlobalWmoCapabilityException {
                                    throw new GlobalWmoCapabilityException("no hardware GPU");
                                }

                                @Override
                                public void shutdown() {
                                    shutdowns[0]++;
                                }
                            });
                    statuses.drain();
                }
                assertEquals(0, shutdowns[0], "a context that never started is not shut down");
                assertEquals(
                        new WorldGlobalStatus.InputConsumed(identity.nonce(), identity.fileSha256())
                                        .protocolLine()
                                + "\n"
                                + new WorldGlobalStatus.Unavailable(
                                                identity, WorldGlobalStatus.Code.CAPABILITY)
                                        .protocolLine()
                                + "\n"
                                + new WorldGlobalStatus.Closed(identity).protocolLine()
                                + "\n",
                        out.toString(StandardCharsets.US_ASCII));
            }
        }
    }
}
