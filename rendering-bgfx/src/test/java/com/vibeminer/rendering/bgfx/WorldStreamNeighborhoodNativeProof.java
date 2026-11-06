package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native evidence for the World view distances (issue #173): for NEAR (3 x 3) and FAR (5 x 5) fills
 * every active slot with a maximum-triangle textured tile and times camera frames. All tiles share
 * the authored footprint, so every triangle stays inside the far cut: a vertex-load worst case.
 * Prints the timings; asserts only completion and resource counts, never a frame rate.
 */
public final class WorldStreamNeighborhoodNativeProof {
    private static final int FRAMES = 60;

    private WorldStreamNeighborhoodNativeProof() {}

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "neighborhood-");
        var report = new StringBuilder("WORLD_NEIGHBORHOOD_PASS");
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-proof-pack").factory())) {
            for (var distance : WorldStreamTransport.ViewDistance.values()) {
                report.append(' ').append(run(directory, worker, distance));
            }
        }
        System.out.println(report);
    }

    /** One owner per view distance: fills every active slot, then times frames. */
    private static String run(
            Path directory,
            java.util.concurrent.ExecutorService worker,
            WorldStreamTransport.ViewDistance distance)
            throws Exception {
        var session =
                new WorldStreamEnvelope.Session(
                        WorldStreamNativeProof.NONCE,
                        WorldStreamNativeProof.SCOPE,
                        1,
                        false,
                        distance.residents());
        Path mapping = directory.resolve("frames-" + distance);
        WorldStreamNativeProof.sequence = 0;
        try (var frames =
                        WorldFrameFileV2.create(
                                mapping,
                                WorldStreamNativeProof.NONCE,
                                WorldStreamNativeProof.SCOPE);
                var reader =
                        WorldFrameFileV2.open(
                                mapping,
                                WorldStreamNativeProof.NONCE,
                                WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(session, frames, BGFX_RENDERER_TYPE_VULKAN)) {
            long idleHeap = owner.stats().gpuHeapBytes();
            var active = new ArrayList<WorldStreamState.Lease>();
            long content = 0;
            var report = new StringBuilder(distance.name().toLowerCase(java.util.Locale.ROOT));
            for (int slot = 0; slot < distance.residents(); slot++) {
                var lease = new WorldStreamState.Lease(slot, 1, slot + 1);
                var packed =
                        WorldStreamNativeProof.prepare(
                                        worker,
                                        directory,
                                        100L * distance.ordinal() + slot + 1,
                                        lease,
                                        5 + slot % 5,
                                        17 + slot / 5,
                                        180,
                                        true)
                                .get(30, TimeUnit.SECONDS);
                WorldStreamNativeProof.upload(owner, packed, reader, content, false);
                active.add(lease);
                owner.commit(content, content + 1, active);
                content++;
                WorldStreamNativeProof.accepted(owner, reader, content);
            }
            var stats = owner.stats();
            if (stats.dynamicBuffers() != session.slots()
                    || session.slots() > BgfxWorldStreamRenderer.SLOT_COUNT
                    || stats.upload().liveUploadBytes() != 0) {
                throw new AssertionError("Fixed slot resources " + stats);
            }
            return report.append(":residents=")
                    .append(active.size())
                    .append(",frame=")
                    .append(time(owner, reader, content))
                    .append(",gpuHeapBytes=")
                    .append(stats.gpuHeapBytes())
                    .append(",idleGpuHeapBytes=")
                    .append(idleHeap)
                    .toString();
        }
    }

    /** Median and 95th percentile milliseconds from camera submit to its read-back frame. */
    private static String time(WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long content)
            throws Exception {
        long[] nanos = new long[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            long revision = ++WorldStreamNativeProof.camera;
            long started = System.nanoTime();
            owner.submit(WorldStreamNativeProof.command(revision, 256, 256, 1));
            long deadline = started + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                owner.advance();
                var frame = WorldStreamNativeProof.read(reader, content, content);
                if (frame != null && frame.metadata().camera() == revision) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Camera frame did not arrive");
                }
            }
            nanos[i] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        return String.format(
                Locale.ROOT,
                "%.2f/%.2fms",
                nanos[FRAMES / 2] / 1e6,
                nanos[FRAMES * 95 / 100] / 1e6);
    }
}
