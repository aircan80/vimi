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
 * reservation released. Removing the budget check, the reservation accounting or the release on
 * slot reuse fails this proof.
 */
public final class WorldStreamObjectsBudgetProof {
    private WorldStreamObjectsBudgetProof() {}

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
            var data = WorldStreamObjectsMaterialProof.quads().data();
            long need;
            try (var probe = WorldStreamPackedObjects.pack(data, () -> false)) {
                need = probe.payloadBytes();
            }
            // Room for one set, not two.
            owner.objectBudgetForProof(need + need / 2);
            require(
                    late(owner, first, data) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "First objects set attaches");
            require(owner.stats().upload().objectBytes() == need, "First set reserved");
            require(
                    late(owner, second, data) == WorldStreamStatus.LateOutcome.BUDGET,
                    "Second objects set refused for the budget");
            require(owner.stats().upload().objectBytes() == need, "A refused set reserves nothing");
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
            require(
                    owner.stats().upload().objectBytes() == 0,
                    "Slot reuse released the reservation");
            owner.commit(content, content + 1, List.of(second, reused));
            content++;
            WorldStreamNativeProof.accepted(owner, reader, content);
            require(
                    late(owner, second, data) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "The refused resident attaches once budget is free");
            require(owner.stats().upload().objectBytes() == need, "Retried set reserved");
            System.out.println(
                    "WORLD_OBJECTS_BUDGET_PASS need="
                            + need
                            + " budget="
                            + (need + need / 2)
                            + " refused=BUDGET retried=ATTACHED");
        }
    }

    /** Submits a new camera and waits for its own frame of the current content. */
    private static void fresh(WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long content)
            throws Exception {
        long revision = ++WorldStreamNativeProof.camera;
        owner.submit(WorldStreamNativeProof.command(revision, 256, 256, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
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
