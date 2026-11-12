package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native proof of the renderer's shared objects GPU budget (#276): with the budget lowered to fit
 * one authored objects set, a second set on another resident is refused as BUDGET while its terrain
 * keeps drawing, reserves nothing, and attaches once the first set's slot is reused and its
 * reservation released. A replacement of an attached set (#175) is built while the set it replaces
 * stays reserved, so it is refused when both do not fit and the held set stays; once it fits, it
 * attaches and the replaced set's reservation is released. Removing the budget check, the
 * reservation accounting, the release on slot reuse or on replacement fails this proof.
 */
public final class WorldStreamObjectsBudgetProof {
    private WorldStreamObjectsBudgetProof() {}

    /** Images per set: small distinct 4 x 4 chains, so a set's GPU bytes are mostly textures. */
    static final int IMAGES = 8;

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "objects-budget-");
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
            var first = new WorldStreamState.Lease(0, 1, 1);
            var second = new WorldStreamState.Lease(1, 1, 2);
            long content = 0;
            var active = new ArrayList<WorldStreamState.Lease>();
            for (var lease : List.of(first, second)) {
                var packed =
                        WorldStreamNativeProof.prepare(
                                        worker,
                                        directory,
                                        lease.serial(),
                                        lease,
                                        7 + lease.slot(),
                                        19,
                                        180,
                                        true)
                                .get(30, TimeUnit.SECONDS);
                WorldStreamNativeProof.upload(owner, packed, reader, content, false);
                active.add(lease);
                owner.commit(content, content + 1, active);
                content++;
                WorldStreamNativeProof.accepted(owner, reader, content);
            }
            // Two sets of the same size whose images differ: identical images would share one
            // texture (WorldStreamObjectTextures) and the second set would cost only its buffers.
            var data = WorldStreamObjectsTextureBudgetProof.images(IMAGES, 1);
            var other = WorldStreamObjectsTextureBudgetProof.images(IMAGES, 2);
            long need;
            try (var probe = WorldStreamPackedObjects.pack(data, () -> false)) {
                need = probe.payloadBytes();
            }
            // Room for one set, not two.
            owner.objectBudgetForProof(need + need / 2);
            require(
                    late(owner, first, data) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "First objects set attaches");
            require(live(owner) == need, "First set reserved");
            require(
                    late(owner, second, other) == WorldStreamStatus.LateOutcome.BUDGET,
                    "Second objects set refused for the budget");
            require(live(owner) == need, "A refused set reserves nothing");
            // Terrain of the refused resident keeps drawing: a new camera still gets a frame.
            fresh(owner, reader, content);
            // Reuse the first slot (new generation): its objects and reservation are released.
            var reused = new WorldStreamState.Lease(0, 2, 3);
            var packed =
                    WorldStreamNativeProof.prepare(worker, directory, 3, reused, 7, 19, 180, true)
                            .get(30, TimeUnit.SECONDS);
            owner.commit(content, content + 1, List.of(second));
            content++;
            WorldStreamNativeProof.accepted(owner, reader, content);
            WorldStreamNativeProof.upload(owner, packed, reader, content, false);
            require(live(owner) == 0, "Slot reuse released the reservation");
            owner.commit(content, content + 1, List.of(second, reused));
            content++;
            WorldStreamNativeProof.accepted(owner, reader, content);
            require(
                    late(owner, second, other) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "The refused resident attaches once budget is free");
            require(live(owner) == need, "Retried set reserved");
            // A replacement is charged beside the set it replaces: refused, and the held set stays.
            require(
                    late(owner, second, data, true) == WorldStreamStatus.LateOutcome.BUDGET,
                    "A replacement that does not fit beside the held set is refused");
            require(
                    live(owner) == need,
                    "A refused replacement keeps the held set's reservation only");
            fresh(owner, reader, content);
            owner.objectBudgetForProof(3 * need);
            require(
                    late(owner, second, data, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "A replacement that fits attaches");
            require(live(owner) == need, "The replaced set's reservation was released");
            // A first set is never accepted for a generation that holds one.
            require(
                    late(owner, second, other) == WorldStreamStatus.LateOutcome.DROPPED,
                    "A non-replacing set for a held generation is dropped");
            System.out.println(
                    "WORLD_OBJECTS_BUDGET_PASS need="
                            + need
                            + " budget="
                            + (need + need / 2)
                            + " refused=BUDGET retried=ATTACHED replacement=BUDGET,ATTACHED");
        }
    }

    /**
     * Object bytes the sets drawing now hold: everything charged but the geometry no set holds,
     * which the session keeps for tiles that come back and gives back first for room (#380).
     */
    static long live(WorldStreamNativeOwner owner) {
        return owner.stats().upload().objectBytes() - owner.objectGeometryForProof().zombieBytes();
    }

    /** Submits a new camera and waits for its own frame of the current content. */
    private static void fresh(WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long content)
            throws Exception {
        long revision = ++WorldStreamNativeProof.camera;
        var command = WorldStreamNativeProof.command(revision, 256, 256, 1);
        // A camera is not drawn in a frame that already drew (the retry above did): submit again.
        boolean drawn = owner.submit(command);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            owner.advance();
            if (!drawn) {
                drawn = owner.submit(command);
            }
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
        return late(owner, lease, data, false);
    }

    private static WorldStreamStatus.LateOutcome late(
            WorldStreamNativeOwner owner,
            WorldStreamState.Lease lease,
            WorldStreamObjectsFile.Data data,
            boolean replace)
            throws Exception {
        var upload =
                owner.beginLate(lease, WorldStreamPackedObjects.pack(data, () -> false), replace);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
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
