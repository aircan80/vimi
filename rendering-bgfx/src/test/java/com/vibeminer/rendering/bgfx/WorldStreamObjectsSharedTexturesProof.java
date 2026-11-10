package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native proof that object sets share identical textures (WorldStreamObjectTextures): sets whose
 * images are the same chains hold one bgfx texture per image between them, so more image references
 * attach than bgfx has texture handles, and their GPU bytes are charged once. A set mixing shared
 * and new images creates only the new ones; a build retired while filling destroys only the
 * textures it created; releasing the set that uploaded a texture keeps it for the sets still
 * drawing it; and the last release destroys every texture and returns every handle and byte.
 */
public final class WorldStreamObjectsSharedTexturesProof {
    private WorldStreamObjectsSharedTexturesProof() {}

    /** Images per shared set, as in the texture budget proof; NEW more in the mixed set. */
    static final int IMAGES = WorldStreamObjectsTextureBudgetProof.IMAGES, NEW = 24;

    /** Sets of the same images: their image references exceed bgfx's texture handle limit. */
    static final int SHARING = 7;

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "objects-shared-");
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
            int bgfxLimit = org.lwjgl.bgfx.BGFX.bgfx_get_caps().limits().maxTextures();
            require(SHARING * IMAGES > bgfxLimit, "The shared references exceed bgfx's limit");
            // Residents: the sharing sets, the mixed set and one whose build is retired.
            int residents = SHARING + 2;
            require(residents <= WorldStreamEnvelope.Session.NEAR_ACTIVE, "Proof fits near");
            long content = 0;
            var active = new ArrayList<WorldStreamState.Lease>();
            for (int slot = 0; slot < residents; slot++) {
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
                owner.commit(content, content + 1, active);
                content++;
                WorldStreamNativeProof.accepted(owner, reader, content);
            }
            var shared = WorldStreamObjectsTextureBudgetProof.images(IMAGES, 0);
            // The same chains and NEW more: image i of both sets is the same 4 x 4 chain.
            var mixed = WorldStreamObjectsTextureBudgetProof.images(IMAGES + NEW, 0);
            long buffers, textures;
            try (var probe = WorldStreamPackedObjects.pack(shared, () -> false)) {
                buffers = probe.bufferBytes();
                textures = probe.payloadBytes() - buffers;
            }
            int before = owner.stats().textures();

            for (int i = 0; i < SHARING; i++) {
                require(
                        late(owner, active.get(i), shared)
                                == WorldStreamStatus.LateOutcome.ATTACHED,
                        "Sharing set " + i + " attaches");
                var upload = owner.stats().upload();
                require(
                        upload.objectTextures() == IMAGES
                                && upload.objectTextureReferences() == (i + 1) * IMAGES,
                        "One handle per image however many sets draw it: "
                                + upload.objectTextures()
                                + " handles, "
                                + upload.objectTextureReferences()
                                + " references");
                require(
                        upload.objectBytes() == (i + 1) * buffers + textures,
                        "Shared textures are charged once: " + upload.objectBytes());
            }
            int peak = owner.stats().textures();
            require(
                    peak - before == IMAGES,
                    "bgfx holds one texture per image: " + (peak - before));
            require(
                    owner.stats().upload().sharedTextureReferences()
                            == (long) (SHARING - 1) * IMAGES,
                    "Every later set shared every image");
            require(owner.stats().upload().textureRefusals() == 0, "No set refused for handles");
            fresh(owner, reader, content);

            // A build retired while filling its own textures destroys only those.
            var retired = active.get(SHARING + 1);
            var building =
                    owner.beginLate(retired, WorldStreamPackedObjects.pack(mixed, () -> false));
            long filling = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (owner.stats().textures() == peak) {
                // Until its first new texture exists; its shared images are skipped at once.
                owner.stepLate(building);
                owner.advance();
                require(System.nanoTime() < filling, "The mixed build created no texture");
            }
            require(!building.ready(), "The mixed build is still filling textures");
            require(
                    owner.stats().upload().objectTextures() == IMAGES + NEW,
                    "The building set reserved only its new images: "
                            + owner.stats().upload().objectTextures());
            active.remove(retired);
            owner.commit(content, content + 1, active);
            content++;
            fresh(owner, reader, content);
            while (!building.ready()) {
                owner.stepLate(building);
                owner.advance();
            }
            require(
                    owner.finishLate(building, true) == WorldStreamStatus.LateOutcome.DROPPED,
                    "The retired build is dropped");
            require(
                    owner.stats().upload().objectTextures() == IMAGES
                            && owner.stats().upload().objectTextureReferences() == SHARING * IMAGES
                            && owner.stats().upload().objectBytes() == SHARING * buffers + textures,
                    "A dropped build released its new textures and kept the shared ones");

            // The mixed set attaches with only its new images created.
            var mixedLease = active.get(SHARING);
            require(
                    late(owner, mixedLease, mixed) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "The mixed set attaches");
            long mixedBuffers, mixedTextures;
            try (var probe = WorldStreamPackedObjects.pack(mixed, () -> false)) {
                mixedBuffers = probe.bufferBytes();
                mixedTextures = probe.payloadBytes() - mixedBuffers;
            }
            require(
                    owner.stats().upload().objectTextures() == IMAGES + NEW
                            && owner.stats().upload().objectBytes()
                                    == SHARING * buffers + mixedBuffers + mixedTextures,
                    "The mixed set created only its new images");
            fresh(owner, reader, content);
            int mixedPeak = owner.stats().textures();

            // Releasing the set that uploaded the textures keeps them for the others.
            long serial = residents + 1;
            var first = active.get(0);
            var reused = new WorldStreamState.Lease(first.slot(), 2, serial);
            content = replace(owner, reader, worker, directory, active, first, reused, content);
            require(
                    owner.stats().upload().objectTextures() == IMAGES + NEW
                            && owner.stats().upload().objectTextureReferences()
                                    == (SHARING - 1) * IMAGES + IMAGES + NEW,
                    "The uploading set's release keeps textures other sets draw");
            require(
                    owner.stats().textures() <= mixedPeak,
                    "No texture created by a release: " + owner.stats().textures());
            fresh(owner, reader, content);

            // Releasing every other set returns every handle and byte.
            for (int i = 1; i <= SHARING; i++) {
                var lease = active.get(0);
                content =
                        replace(
                                owner,
                                reader,
                                worker,
                                directory,
                                active,
                                lease,
                                new WorldStreamState.Lease(lease.slot(), 2, ++serial),
                                content);
            }
            var upload = owner.stats().upload();
            require(
                    upload.objectTextures() == 0
                            && upload.objectTextureReferences() == 0
                            && upload.objectBytes() == 0,
                    "The last release frees every shared texture: " + upload);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (owner.stats().textures() != before) {
                fresh(owner, reader, content);
                require(
                        System.nanoTime() < deadline,
                        "bgfx returns every released handle: "
                                + owner.stats().textures()
                                + " of "
                                + before);
            }
            System.out.println(
                    "WORLD_OBJECTS_SHARED_TEXTURES_PASS bgfx="
                            + bgfxLimit
                            + " sets="
                            + SHARING
                            + " references="
                            + SHARING * IMAGES
                            + " handles="
                            + IMAGES
                            + " peakLive="
                            + peak
                            + " mixedHandles="
                            + (IMAGES + NEW)
                            + " retired=DROPPED released=0 live="
                            + owner.stats().textures());
        }
    }

    /**
     * Retires {@code old} and uploads terrain for {@code fresh} in its slot, which releases the old
     * generation's objects; the new generation carries none. Returns the new content.
     */
    private static long replace(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            java.util.concurrent.ExecutorService worker,
            Path directory,
            java.util.List<WorldStreamState.Lease> active,
            WorldStreamState.Lease old,
            WorldStreamState.Lease fresh,
            long content)
            throws Exception {
        var packed =
                WorldStreamNativeProof.prepare(
                                worker,
                                directory,
                                fresh.serial(),
                                fresh,
                                7 + old.slot(),
                                19,
                                180,
                                true)
                        .get(30, TimeUnit.SECONDS);
        active.remove(old);
        owner.commit(content, content + 1, active);
        content++;
        WorldStreamNativeProof.accepted(owner, reader, content);
        WorldStreamNativeProof.upload(owner, packed, reader, content, false);
        active.add(fresh);
        owner.commit(content, content + 1, active);
        content++;
        WorldStreamNativeProof.accepted(owner, reader, content);
        return content;
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
            require(System.nanoTime() < deadline, "No frame for the current content");
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
