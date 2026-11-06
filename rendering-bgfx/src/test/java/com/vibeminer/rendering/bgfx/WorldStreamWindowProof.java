package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;

/**
 * #381 spike, opt-in (Windows, D3D11; shows a window): the real World v2 transport and helper in
 * native-presentation mode. A plain Win32 window stands in for the JavaFX stage; the helper is
 * launched with {@code --present-parent} and presents an authored tile into its child window at the
 * display rate. The helper reads its own swap chain back (no screen capture) and reports
 * CHILD_SHOWS_FRAMES PASS or FAIL, present intervals, and the tile's commit time.
 */
public final class WorldStreamWindowProof {
    private WorldStreamWindowProof() {}

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            System.out.println("WORLD_STREAM_WINDOW_SKIPPED not Windows");
            return;
        }
        Path output = Path.of(arguments[0]);
        // "readback": the same run without --present-parent, for the commit-time comparison.
        boolean readback = arguments.length > 1 && arguments[1].equals("readback");
        Files.createDirectories(output);
        Path probe = output.resolve("present-probe.txt");
        Files.deleteIfExists(probe);
        long parent = parentWindow();
        var prepared =
                WorldStreamTransport.prepare(
                        output,
                        new WorldStreamTransport.Spec(
                                "ce".repeat(32), 1, WorldStreamTransport.Backend.D3D11));
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
                                                    "-D"
                                                            + WorldWindowPresentation.PROBE_PROPERTY
                                                            + "="
                                                            + probe,
                                                    "-cp",
                                                    System.getProperty("java.class.path"),
                                                    GpuViewerMain.class.getName()));
                            command.addAll(controls);
                            if (!readback) {
                                command.addAll(List.of("--present-parent", Long.toString(parent)));
                            }
                            return new ProcessBuilder(command)
                                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                                    .start();
                        });
        try {
            session.initialized().get(15, TimeUnit.SECONDS);
            prepared.close();
            WorldStreamTransport.TileReservation reserved = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (reserved == null && System.nanoTime() < until) {
                reserved = session.tryReserveTile(7, 19).orElse(null);
                if (reserved == null) {
                    Thread.sleep(2);
                }
            }
            if (reserved == null) {
                throw new AssertionError("No child tile credit");
            }
            var resident = WorldStreamTransportProof.produce(reserved).get(8, TimeUnit.SECONDS);
            session.camera(new WorldStreamTransport.Camera(1, 1, 800, 500, view(1)));
            long commitStart = System.nanoTime();
            session.commit(1, resident).get(10, TimeUnit.SECONDS);
            double commitMs = (System.nanoTime() - commitStart) / 1e6;
            var running = new AtomicBoolean(true);
            var revision = new AtomicLong(1);
            var driver =
                    Thread.ofPlatform()
                            .name("window-camera")
                            .start(
                                    () -> {
                                        while (running.get()) {
                                            long r = revision.incrementAndGet();
                                            session.camera(
                                                    new WorldStreamTransport.Camera(
                                                            r, 1, 800, 500, view(r)));
                                            java.util.concurrent.locks.LockSupport.parkNanos(
                                                    4_166_667L);
                                        }
                                    });
            Thread.sleep(6000);
            running.set(false);
            driver.join();
            session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            System.out.printf(
                    Locale.ROOT,
                    "WORLD_STREAM_WINDOW mode=%s cameras=%d tileCommitMs=%.1f%n%s",
                    readback ? "readback" : "native",
                    revision.get(),
                    commitMs,
                    readback
                            ? ""
                            : Files.exists(probe)
                                    ? Files.readString(probe)
                                    : "CHILD_SHOWS_FRAMES FAIL no probe file\n");
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    /** A visible top-level Win32 window on its own message thread, standing in for the stage. */
    private static long parentWindow() throws Exception {
        var created = new CompletableFuture<Long>();
        Thread.ofPlatform()
                .daemon()
                .name("proof-parent-window")
                .start(
                        () -> {
                            try (var stack = MemoryStack.stackPush()) {
                                var proc = WindowProc.create(User32::DefWindowProc);
                                var cls =
                                        WNDCLASSEX
                                                .calloc(stack)
                                                .cbSize(WNDCLASSEX.SIZEOF)
                                                .lpfnWndProc(proc)
                                                .lpszClassName(stack.UTF16("VimiWorldWindowProof"));
                                User32.RegisterClassEx(null, cls);
                                long hwnd =
                                        User32.CreateWindowEx(
                                                null,
                                                0,
                                                "VimiWorldWindowProof",
                                                "World native present proof",
                                                User32.WS_OVERLAPPEDWINDOW
                                                        | User32.WS_VISIBLE
                                                        | User32.WS_CLIPCHILDREN,
                                                100,
                                                100,
                                                1024,
                                                640,
                                                0,
                                                0,
                                                0,
                                                0);
                                created.complete(hwnd);
                                var msg = MSG.calloc(stack);
                                while (User32.GetMessage(null, msg, 0, 0, 0)) {
                                    User32.TranslateMessage(msg);
                                    User32.DispatchMessage(msg);
                                }
                            } catch (Throwable failure) {
                                created.completeExceptionally(failure);
                            }
                        });
        long hwnd = created.get(10, TimeUnit.SECONDS);
        if (hwnd == 0) {
            throw new AssertionError("No parent window");
        }
        return hwnd;
    }

    private static TerrainCamera view(long revision) {
        double t = revision * 0.002;
        return new TerrainCamera(-20 + Math.sin(t) * 4, -2, 20, Math.sin(t) * 0.4, -.5);
    }
}
