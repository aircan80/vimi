package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Opt-in diagnostic: the public World v2 transport with one authored tile under continuous camera
 * motion. Measures taken-frame interval and camera-request-to-taken-frame latency. No user data, no
 * JavaFX; the consumer takes each frame when the transport's frame listener signals it.
 */
public final class WorldStreamCadenceProof {
    private static final long WARMUP_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long SAMPLE_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final int MAX_REVISIONS = 1 << 16;

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        System.out.printf(
                Locale.ROOT,
                "WORLD_FRAME_READ 800x500 mean ms: default %.3f, debug-verify %.3f%n",
                readMillis(output, false),
                readMillis(output, true));
        var prepared =
                WorldStreamTransport.prepare(
                        output,
                        new WorldStreamTransport.Spec(
                                "cd".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
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
            var sentAt = new AtomicLongArray(MAX_REVISIONS);
            var revision = new AtomicLong(1);
            sentAt.set(1, System.nanoTime());
            session.camera(new WorldStreamTransport.Camera(1, 1, 800, 500, view(0)));
            var receipt = session.commit(1, resident).get(5, TimeUnit.SECONDS);
            var running = new java.util.concurrent.atomic.AtomicBoolean(true);
            // {width, height, policy}: the driver always sends the current size.
            var size = new java.util.concurrent.atomic.AtomicReference<>(new int[] {800, 500, 1});
            // A 240 Hz camera driver: faster than any presentation rate under test.
            var driver =
                    Thread.ofPlatform()
                            .name("cadence-camera")
                            .start(
                                    () -> {
                                        long next = System.nanoTime();
                                        while (running.get()) {
                                            long r = revision.incrementAndGet();
                                            if (r >= MAX_REVISIONS) {
                                                return;
                                            }
                                            int[] current = size.get();
                                            sentAt.set((int) r, System.nanoTime());
                                            session.camera(
                                                    new WorldStreamTransport.Camera(
                                                            r,
                                                            current[2],
                                                            current[0],
                                                            current[1],
                                                            view(r)));
                                            next += 4_166_667L;
                                            long wait = next - System.nanoTime();
                                            if (wait > 0) {
                                                java.util.concurrent.locks.LockSupport.parkNanos(
                                                        wait);
                                            }
                                        }
                                    });
            int[][] sizes = {{800, 500}, {1920, 1080}, {2560, 1440}};
            for (int index = 0; index < sizes.length; index++) {
                int width = sizes[index][0], height = sizes[index][1], policy = index + 1;
                size.set(new int[] {width, height, policy});
                long start = System.nanoTime(), sampleFrom = start + WARMUP_NANOS;
                long end = sampleFrom + SAMPLE_NANOS;
                var intervals = new ArrayList<Long>();
                var latencies = new ArrayList<Long>();
                long last = 0, firstSequence = -1, lastSequence = 0;
                long sampleAllocations = 0, sampleCollections = 0;
                int frames = 0;
                while (System.nanoTime() < end) {
                    var frame = next(session, 1_000_000_000L);
                    if (frame == null) {
                        continue;
                    }
                    long now = System.nanoTime();
                    if (frame.content() != receipt
                            || frame.policy() != policy
                            || frame.width() != width
                            || frame.height() != height) {
                        done(frame);
                        continue;
                    }
                    long cameraRevision = frame.cameraRevision();
                    done(frame);
                    if (now < sampleFrom) {
                        last = now;
                        continue;
                    }
                    if (firstSequence < 0) {
                        sampleAllocations = poolAllocations(session);
                        sampleCollections = collections();
                        firstSequence = frame.sequence();
                    }
                    lastSequence = frame.sequence();
                    frames++;
                    if (last != 0) {
                        intervals.add(now - last);
                    }
                    last = now;
                    if (cameraRevision < MAX_REVISIONS) {
                        latencies.add(now - sentAt.get((int) cameraRevision));
                    }
                }
                long frameArrays = poolAllocations(session) - sampleAllocations;
                long gcs = collections() - sampleCollections;
                double seconds = SAMPLE_NANOS / 1e9;
                System.out.printf(
                        Locale.ROOT,
                        "WORLD_STREAM_CADENCE %dx%d frames=%d fps=%.1f publishedSpan=%d"
                                + " frameArraysAllocated=%d gcs=%d%n"
                                + "  interval ms %s%n  camera->taken ms %s%n",
                        width,
                        height,
                        frames,
                        frames / seconds,
                        lastSequence - firstSequence + 1,
                        frameArrays,
                        gcs,
                        summary(intervals),
                        summary(latencies));
            }
            running.set(false);
            driver.join();
            session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    private static final java.util.concurrent.Semaphore OFFERED =
            new java.util.concurrent.Semaphore(0);

    /** Takes the newest offered frame when the transport signals one, or null after timeout. */
    private static WorldStreamTransport.Frame next(
            WorldStreamTransport.Session session, long timeoutNanos) throws InterruptedException {
        session.onFrame(OFFERED::release);
        long until = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < until) {
            var value = session.takeLatestFrame();
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            OFFERED.tryAcquire(until - System.nanoTime(), TimeUnit.NANOSECONDS);
            OFFERED.drainPermits();
        }
        return null;
    }

    /** Mean parent read of one full frame (copy, checks), released after each read. */
    private static double readMillis(Path output, boolean verify) throws Exception {
        var nonce = new WorldStreamState.Nonce(5, 6);
        Path path = output.resolve("read-" + verify + ".wsf");
        Files.deleteIfExists(path);
        try (var file = WorldFrameFileV2.open(path, nonce, "ef".repeat(32), true, verify)) {
            var bgra =
                    java.nio.ByteBuffer.allocate(800 * 500 * 4)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            while (bgra.hasRemaining()) {
                bgra.putInt(0xff336699);
            }
            file.write(new WorldStreamFrameGate.Frame(nonce, 1, 1, 0, 1, 800, 500), bgra.flip());
            var bounds = new WorldFrameFileV2.Barrier(1, 800, 500, 1, 1, 0, 0);
            for (int i = 0; i < 50; i++) {
                file.readLatest(0, bounds).release();
            }
            int reads = 300;
            long start = System.nanoTime();
            for (int i = 0; i < reads; i++) {
                file.readLatest(0, bounds).release();
            }
            return (System.nanoTime() - start) / 1e6 / reads;
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void done(WorldStreamTransport.Frame frame) {
        frame.release();
    }

    private static long poolAllocations(WorldStreamTransport.Session session) throws Exception {
        var field = WorldStreamTransport.Session.class.getDeclaredField("owner");
        field.setAccessible(true);
        var owner = (WorldStreamLaunchOwner.Handle) field.get(session);
        return owner.files().frames().pool().allocations();
    }

    private static long collections() {
        long total = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionCount());
        }
        return total;
    }

    private static TerrainCamera view(long revision) {
        double t = revision * 0.002;
        return new TerrainCamera(-20 + Math.sin(t) * 4, -2, 20, Math.sin(t) * 0.4, -.5);
    }

    private static String summary(List<Long> values) {
        if (values.isEmpty()) {
            return "none";
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "p50 %.2f p95 %.2f max %.2f (n=%d)",
                sorted[sorted.length / 2] / 1e6,
                sorted[(int) Math.min(sorted.length - 1, Math.round(sorted.length * .95))] / 1e6,
                sorted[sorted.length - 1] / 1e6,
                sorted.length);
    }

    private WorldStreamCadenceProof() {}
}
