package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native proof of the object texture handle budget at bgfx's real limit, not a lowered seam: a near
 * session is offered more object sets of {@link #IMAGES} small images each than bgfx's texture
 * handles can hold (BGFX_CONFIG_MAX_TEXTURES, 4096 in the pinned LWJGL build). Every set that fits
 * attaches; the first that does not is refused as BUDGET before it creates anything, never a native
 * allocation failure, its terrain keeps drawing, and it attaches once a resident's slot is reused
 * and that set's handles are returned. Removing the handle check makes the over-limit set fail at
 * the textures step instead.
 */
public final class WorldStreamObjectsTextureBudgetProof {
    private WorldStreamObjectsTextureBudgetProof() {}

    /** Images per set: one short of a set's file cap, all 4 x 4 RGBA8, so bytes never bind. */
    static final int IMAGES = WorldStreamObjectsFile.MAX_IMAGES - 24;

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "objects-textures-");
        Path mapping = directory.resolve("frames-v2");
        var session =
                new WorldStreamEnvelope.Session(
                        WorldStreamNativeProof.NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        WorldStreamNativeProof.sequence = 0;
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
                        new WorldStreamNativeOwner(session, frames, BGFX_RENDERER_TYPE_VULKAN)) {
            var stats = owner.stats();
            int limit = stats.upload().objectTextureLimit();
            int bgfxLimit = org.lwjgl.bgfx.BGFX.bgfx_get_caps().limits().maxTextures();
            require(limit > 0 && limit < bgfxLimit, "Object handle limit inside bgfx's");
            // Sets that fit, plus one past the limit.
            int fitting = limit / IMAGES;
            require(fitting + 1 < WorldStreamEnvelope.Session.NEAR_ACTIVE, "Proof fits a near set");
            long content = 0;
            var active = new ArrayList<WorldStreamState.Lease>();
            var leases = new ArrayList<WorldStreamState.Lease>();
            for (int slot = 0; slot <= fitting; slot++) {
                var lease = new WorldStreamState.Lease(slot, 1, slot + 1);
                var packed =
                        WorldStreamNativeProof.prepare(
                                        worker,
                                        directory,
                                        lease.serial(),
                                        lease,
                                        7 + slot,
                                        19,
                                        180,
                                        true)
                                .get(30, TimeUnit.SECONDS);
                WorldStreamNativeProof.upload(owner, packed, reader, content, false);
                active.add(lease);
                leases.add(lease);
                owner.commit(content, content + 1, active);
                content++;
                WorldStreamNativeProof.accepted(owner, reader, content);
            }
            var data = images(IMAGES);
            int before = owner.stats().textures();
            // Timeout path: with the live room forced short, a set that fits the accounting
            // waits for handles, then is refused as BUDGET once the bound runs out, having
            // created and reserved nothing.
            owner.liveTextureSpareForProof(bgfxLimit, TimeUnit.MILLISECONDS.toNanos(100));
            long started = System.nanoTime();
            require(
                    late(owner, leases.get(0), data) == WorldStreamStatus.LateOutcome.BUDGET,
                    "A wait that runs out is refused as BUDGET");
            long waited = System.nanoTime() - started;
            long timeoutWaits = owner.textureWaitStepsForProof();
            require(
                    timeoutWaits > 0 && waited >= TimeUnit.MILLISECONDS.toNanos(100),
                    "The refused set waited out the bound first");
            require(
                    owner.stats().textures() == before
                            && owner.stats().upload().objectTextures() == 0
                            && owner.stats().upload().textureRefusals() == 1,
                    "A timed-out set creates and reserves nothing");
            owner.liveTextureSpareForProof(0, BgfxWorldStreamRenderer.TEXTURE_WAIT_NANOS);
            for (int i = 0; i < fitting; i++) {
                require(
                        late(owner, leases.get(i), data) == WorldStreamStatus.LateOutcome.ATTACHED,
                        "Set " + i + " fits and attaches");
            }
            int peak = owner.stats().textures();
            require(
                    peak - before == fitting * IMAGES,
                    "bgfx counts every created handle at once: " + (peak - before));
            require(
                    owner.stats().upload().objectTextures() == fitting * IMAGES,
                    "Attached sets hold their handles");
            long bytes = owner.stats().upload().objectBytes();
            var over = leases.get(fitting);
            require(
                    late(owner, over, data) == WorldStreamStatus.LateOutcome.BUDGET,
                    "The set past bgfx's handle limit is refused as BUDGET, not failed");
            require(
                    owner.stats().textures() == peak
                            && owner.stats().upload().objectBytes() == bytes
                            && owner.stats().upload().objectTextures() == fitting * IMAGES,
                    "A refused set creates and reserves nothing");
            fresh(owner, reader, content);
            // Reuse the first slot (new generation): its set and handles are released.
            var reused = new WorldStreamState.Lease(0, 2, fitting + 2);
            var packed =
                    WorldStreamNativeProof.prepare(
                                    worker, directory, fitting + 2, reused, 7, 19, 180, true)
                            .get(30, TimeUnit.SECONDS);
            active.remove(0);
            owner.commit(content, content + 1, active);
            content++;
            WorldStreamNativeProof.accepted(owner, reader, content);
            // The new generation's upload destroys the slot's set at once; bgfx returns its
            // handles only at a later frame. Offered again before any frame, the set fits the
            // accounting but not bgfx's live count, so it waits rather than failing.
            var reuse = owner.begin(packed);
            require(
                    owner.stats().upload().objectTextures() == (fitting - 1) * IMAGES,
                    "Slot reuse released the set's handles");
            int stale = owner.stats().textures();
            require(stale == peak, "bgfx has not returned the released handles yet: " + stale);
            long waitsBefore = owner.textureWaitStepsForProof();
            var retry = owner.beginLate(over, WorldStreamPackedObjects.pack(data, () -> false));
            owner.stepLate(retry);
            require(
                    owner.textureWaitStepsForProof() > waitsBefore && !retry.ready(),
                    "The retried set waits for the released handles");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!reuse.finished() || !retry.ready()) {
                if (!reuse.finished()) {
                    owner.step(reuse);
                } else {
                    owner.stepLate(retry);
                }
                owner.submit(
                        WorldStreamNativeProof.command(
                                ++WorldStreamNativeProof.camera, 256, 256, 1));
                owner.advance();
                require(System.nanoTime() < deadline, "Reuse and retry did not finish");
            }
            require(reuse.successful(), "Reused slot's terrain uploaded");
            long liveWaits = owner.textureWaitStepsForProof() - waitsBefore;
            require(
                    owner.finishLate(retry, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "The refused set attaches once handles are free");
            active.add(reused);
            owner.commit(content, content + 1, active);
            content++;
            WorldStreamNativeProof.accepted(owner, reader, content);
            int after = owner.stats().textures();
            require(after < bgfxLimit, "Live handles stay under bgfx's limit: " + after);
            System.out.println(
                    "WORLD_OBJECTS_TEXTURES_PASS bgfx="
                            + bgfxLimit
                            + " objectLimit="
                            + limit
                            + " images="
                            + IMAGES
                            + " attached="
                            + fitting
                            + " peakLive="
                            + peak
                            + " refused=BUDGET retried=ATTACHED live="
                            + after
                            + " lateFreeWaits="
                            + liveWaits
                            + " timeoutWaits="
                            + timeoutWaits);
        }
    }

    /** One objects set of {@code count} 4 x 4 RGBA8 images; one quad draws the first. */
    static WorldStreamObjectsFile.Data images(int count) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        var top = ByteBuffer.allocate(4 * 4 * 4);
        for (int i = 0; i < count; i++) {
            top.clear();
            while (top.hasRemaining()) {
                top.put((byte) i).put((byte) (i >> 8)).put((byte) 128).put((byte) 255);
            }
            top.flip();
            builder.image(4, 4, WorldStreamObjectsFileTest.chain(4, 4, top));
        }
        builder.model();
        var vertices = FloatBuffer.allocate(4 * 8);
        for (int corner = 0; corner < 4; corner++) {
            vertices.put(corner == 1 || corner == 2 ? 1 : 0).put(corner < 2 ? 0 : 1).put(0);
            vertices.put(0).put(0).put(1).put(.5f).put(.5f);
        }
        vertices.flip();
        var indices = IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3});
        builder.mesh(
                vertices, indices, new int[] {0, 6, 0}, new int[] {WorldStreamObjectsFile.OPAQUE});
        builder.placement(0, 0, 0, 0, 1, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        return builder.build().data();
    }

    /** Submits a new camera and waits for its own frame of the current content. */
    private static void fresh(WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long content)
            throws Exception {
        long revision = ++WorldStreamNativeProof.camera;
        var command = WorldStreamNativeProof.command(revision, 256, 256, 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            // Submitted again until drawn: a submit finding every readback in flight draws nothing.
            owner.submit(command);
            owner.advance();
            var frame = WorldStreamNativeProof.read(reader, content, content);
            if (frame != null && frame.metadata().camera() == revision) {
                return;
            }
            require(System.nanoTime() < deadline, "No frame for the refused resident's content");
        }
    }

    private static WorldStreamStatus.LateOutcome late(
            WorldStreamNativeOwner owner,
            WorldStreamState.Lease lease,
            WorldStreamObjectsFile.Data data)
            throws Exception {
        var upload = owner.beginLate(lease, WorldStreamPackedObjects.pack(data, () -> false));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!upload.ready()) {
            owner.stepLate(upload);
            owner.submit(
                    WorldStreamNativeProof.command(++WorldStreamNativeProof.camera, 256, 256, 1));
            owner.advance();
            require(System.nanoTime() < deadline, "Late objects did not finish building");
        }
        return owner.finishLate(upload, true);
    }
}
