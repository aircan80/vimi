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
            if (!readback) {
                // The child starts hidden; the GUI places and shows it, so the proof does too.
                showChild(parent, 1000, 600);
            }
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
            // PRESENTED through the public transport: camera revision -> presented latency.
            var sentAt = new java.util.concurrent.ConcurrentHashMap<Long, Long>();
            var presentedLatency = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
            var presentedCount = new AtomicLong();
            var presentedContent = new AtomicLong(-1);
            session.onPresented(
                    (camera, content) -> {
                        presentedCount.incrementAndGet();
                        presentedContent.set(content);
                        var at = sentAt.remove(camera);
                        if (at != null) {
                            presentedLatency.add(System.nanoTime() - at);
                        }
                    });
            sentAt.put(1L, System.nanoTime());
            session.camera(
                    new WorldStreamTransport.Camera(1, 1, 800, 500, view(1)).withSky(RECORDED));
            long commitStart = System.nanoTime();
            var receipt = session.commit(1, resident).get(10, TimeUnit.SECONDS);
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
                                            sentAt.put(r, System.nanoTime());
                                            session.camera(
                                                    new WorldStreamTransport.Camera(
                                                                    r, 1, 800, 500, view(r))
                                                            .withSky(RECORDED));
                                            java.util.concurrent.locks.LockSupport.parkNanos(
                                                    4_166_667L);
                                        }
                                    });
            Thread.sleep(4500);
            // Minimised, DXGI presents without waiting; the loop must idle, not spin.
            User32.ShowWindow(parent, User32.SW_MINIMIZE);
            Thread.sleep(1500);
            User32.ShowWindow(parent, User32.SW_RESTORE);
            Thread.sleep(500);
            running.set(false);
            driver.join();
            session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            String report =
                    Files.exists(probe)
                            ? Files.readString(probe)
                            : "CHILD_SHOWS_FRAMES FAIL no probe file%n".formatted();
            if (!readback) {
                var sorted = new ArrayList<>(presentedLatency);
                sorted.sort(null);
                System.out.printf(
                        Locale.ROOT,
                        "PRESENTED_STATUS count=%d camera->presented p50Ms %.2f p95Ms %.2f (n=%d)%n",
                        presentedCount.get(),
                        sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2) / 1e6,
                        sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * .95)) / 1e6,
                        sorted.size());
                if (presentedCount.get() == 0) {
                    throw new AssertionError("No PRESENTED status reached the parent");
                }
                System.out.println(
                        "PRESENTED_CONTENT "
                                + (presentedContent.get() == receipt.contentRevision()
                                        ? "PASS"
                                        : "FAIL")
                                + " last="
                                + presentedContent.get()
                                + " receipt="
                                + receipt.contentRevision());
                if (presentedContent.get() != receipt.contentRevision()) {
                    throw new AssertionError("PRESENTED content does not match the commit receipt");
                }
            }
            System.out.printf(
                    Locale.ROOT,
                    "WORLD_STREAM_WINDOW mode=%s cameras=%d tileCommitMs=%.1f%n%s",
                    readback ? "readback" : "native",
                    revision.get(),
                    commitMs,
                    readback ? "" : report);
            if (!readback) {
                // The terrain samples must be the authored tile's atlas colour (17, 34, 51): the
                // window shows the uploaded terrain, not just a sky or a clear.
                var match =
                        java.util.regex.Pattern.compile("first rgb\\((\\d+),(\\d+),(\\d+)\\)")
                                .matcher(report);
                boolean atlas =
                        report.startsWith("CHILD_SHOWS_FRAMES PASS")
                                && match.find()
                                && Math.abs(Integer.parseInt(match.group(1)) - 17) <= 2
                                && Math.abs(Integer.parseInt(match.group(2)) - 34) <= 2
                                && Math.abs(Integer.parseInt(match.group(3)) - 51) <= 2;
                System.out.println(
                        "WINDOW_TERRAIN_IS_THE_AUTHORED_ATLAS " + (atlas ? "PASS" : "FAIL"));
                if (!atlas) {
                    throw new AssertionError("The child window does not show the authored terrain");
                }
                // The centre looks below the horizon at sky, which is the fog colour there: the
                // recorded horizon, not the explorer's (#555).
                var centre =
                        java.util.regex.Pattern.compile("centre rgb\\((\\d+),(\\d+),(\\d+)\\)")
                                .matcher(report);
                float[] horizon = RECORDED.horizon();
                boolean fogged = centre.find();
                for (int i = 0; fogged && i < 3; i++) {
                    fogged = Math.abs(Integer.parseInt(centre.group(i + 1)) - horizon[i]) <= 2;
                }
                System.out.println("WINDOW_SKY_IS_THE_RECORDED_FOG " + (fogged ? "PASS" : "FAIL"));
                if (!fogged) {
                    throw new AssertionError("The child window does not draw the recorded fog");
                }
            }
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    /**
     * Finds the helper's child of {@code parent} and shows it at {@code width} x {@code height},
     * without activating it, as the GUI's WorldNativePresenter does. FindWindowExW is not bound by
     * LWJGL, so it goes through java.lang.foreign.
     */
    @SuppressWarnings("restricted")
    static long showChild(long parent, int width, int height) throws Exception {
        var linker = java.lang.foreign.Linker.nativeLinker();
        var user32 =
                java.lang.foreign.SymbolLookup.libraryLookup(
                        "user32", java.lang.foreign.Arena.global());
        var find =
                linker.downcallHandle(
                        user32.find("FindWindowExW").orElseThrow(),
                        java.lang.foreign.FunctionDescriptor.of(
                                java.lang.foreign.ValueLayout.JAVA_LONG,
                                java.lang.foreign.ValueLayout.JAVA_LONG,
                                java.lang.foreign.ValueLayout.JAVA_LONG,
                                java.lang.foreign.ValueLayout.ADDRESS,
                                java.lang.foreign.ValueLayout.ADDRESS));
        long child = 0;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var name =
                    arena.allocateFrom(
                            WorldChildWindow.CLASS_NAME,
                            java.nio.charset.StandardCharsets.UTF_16LE);
            while (child == 0 && System.nanoTime() < until) {
                try {
                    child =
                            (long)
                                    find.invokeExact(
                                            parent, 0L, name, java.lang.foreign.MemorySegment.NULL);
                } catch (Throwable unavailable) {
                    throw new IllegalStateException("FindWindowExW failed", unavailable);
                }
                if (child == 0) {
                    Thread.sleep(5);
                }
            }
        }
        if (child == 0) {
            throw new AssertionError("The helper created no child window");
        }
        if (User32.IsWindowVisible(child)) {
            throw new AssertionError("The child must start hidden");
        }
        User32.SetWindowPos(
                null, child, 0, 0, 0, width, height, User32.SWP_NOACTIVATE | User32.SWP_NOZORDER);
        User32.ShowWindow(child, User32.SW_SHOWNA);
        return child;
    }

    /** A visible top-level Win32 window on its own message thread, standing in for the stage. */
    static long parentWindow() throws Exception {
        var created = new CompletableFuture<Long>();
        Thread.ofPlatform()
                .daemon()
                .name("proof-parent-window")
                .start(
                        () -> {
                            try (var stack = MemoryStack.stackPush()) {
                                // A destroyed parent ends its thread (WM_QUIT), so tests leave no
                                // pumping thread.
                                var proc =
                                        WindowProc.create(
                                                (window, message, w, l) -> {
                                                    if (message == User32.WM_DESTROY) {
                                                        WorldChildWindow.postQuitMessage();
                                                    }
                                                    return User32.DefWindowProc(
                                                            window, message, w, l);
                                                });
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

    /**
     * A recorded sky unlike the explorer's, with its fog starting well past the terrain 10 yd away,
     * so the terrain keeps its atlas colour and the sky below the horizon shows the recorded fog.
     */
    private static final WorldStreamTransport.Sky RECORDED =
            new WorldStreamTransport.Sky(
                    new float[] {200, 120, 60}, new float[] {40, 60, 140}, 1, 300, 900);

    /**
     * Facing north at the authored triangle's centroid (-0.69, -1.74, 10) from 9 yd south and 4 yd
     * up, pitched less than the line of sight so the triangle sits below the centre, in the lower
     * rows the swap-chain check samples. The camera only slides a little, so it stays in view.
     */
    private static TerrainCamera view(long revision) {
        double t = revision * 0.002;
        return new TerrainCamera(-10 + Math.sin(t) * .5, -1.74, 14, 0, -.25);
    }
}
