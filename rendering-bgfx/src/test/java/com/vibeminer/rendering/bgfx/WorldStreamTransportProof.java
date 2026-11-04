package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in actual native startup/control/exit proof; no content or frame acceptance claim. */
public final class WorldStreamTransportProof {
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var process = new AtomicReference<Process>();
        long start = System.nanoTime();
        var prepared =
                WorldStreamTransport.prepare(
                        output,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        var session =
                prepared.launch(
                        controls -> {
                            var command =
                                    new ArrayList<>(
                                            List.of(
                                                    Path.of(
                                                                    System.getProperty("java.home"),
                                                                    "bin",
                                                                    "java")
                                                            .toString(),
                                                    "--enable-native-access=ALL-UNNAMED",
                                                    "-cp",
                                                    System.getProperty("java.class.path"),
                                                    GpuViewerMain.class.getName()));
                            command.addAll(controls);
                            var child = new ProcessBuilder(command).start();
                            process.set(child);
                            return child;
                        });
        try {
            session.initialized().get(15, TimeUnit.SECONDS);
            prepared.close();
            for (int i = 1; i <= 100; i++) {
                session.camera(
                        new WorldStreamTransport.Camera(
                                i, 1, 800, 500, new TerrainCamera(i, 0, 2, .01 * i, .1)));
            }
            // Camera processing is asynchronous. This checks process continuity, not applied count.
            Thread.sleep(100);
            if (!process.get().isAlive()) {
                throw new AssertionError("Native child ended during cameras");
            }
            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.childPid().orElseThrow() != process.get().pid()
                    || receipt.exitCode().orElseThrow() != 0
                    || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
            try (var children = Files.list(output)) {
                if (children.anyMatch(
                        path -> path.getFileName().toString().startsWith("world-v2-"))) {
                    throw new AssertionError("Private files retained after successful drain");
                }
            }
            System.out.println(
                    "WORLD_PUBLIC_LIFECYCLE_PASS pid="
                            + receipt.childPid().orElseThrow()
                            + " submittedCameras=100 elapsedMs="
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            + " contentFrames=NOT_TESTED");
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    private WorldStreamTransportProof() {}
}
