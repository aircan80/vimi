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
 * Native evidence for the 5 x 5 World neighborhood (issue #173): fills every active slot with a
 * maximum-triangle textured tile and times camera frames at 9 and at 25 residents. All tiles share
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
        Path mapping = directory.resolve("frames-v2");
        try (var worker =
                        Executors.newSingleThreadExecutor(
                                Thread.ofPlatform().daemon().name("world-proof-pack").factory());
                var frames =
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
                        new WorldStreamNativeOwner(
                                WorldStreamNativeProof.SESSION,
                                frames,
                                BGFX_RENDERER_TYPE_VULKAN)) {
            var active = new ArrayList<WorldStreamState.Lease>();
            long content = 0, tx = 1;
            var report = new StringBuilder("WORLD_NEIGHBORHOOD_PASS");
            for (int slot = 0; slot < WorldStreamState.MAX_ACTIVE; slot++) {
                var lease = new WorldStreamState.Lease(slot, 1, slot + 1);
                var packed =
                        WorldStreamNativeProof.prepare(
                                        worker,
                                        directory,
                                        tx++,
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
                if (active.size() == 9 || active.size() == WorldStreamState.MAX_ACTIVE) {
                    report.append(" residents")
                            .append(active.size())
                            .append('=')
                            .append(time(owner, reader, content));
                }
            }
            var stats = owner.stats();
            if (stats.dynamicBuffers() != BgfxWorldStreamRenderer.SLOT_COUNT
                    || stats.upload().liveUploadBytes() != 0) {
                throw new AssertionError("Fixed slot resources " + stats);
            }
            System.out.println(report + " gpuHeapBytes=" + stats.gpuHeapBytes());
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
