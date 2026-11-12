package com.vibeminer.rendering.bgfx;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual native proof of late objects files that name assets by digest (#380 stage 4), on the real
 * hot child over one authored terrain tile:
 *
 * <ol>
 *   <li>Pixel identity: the material proof's scene ({@link WorldStreamObjectsMaterialProof#quads})
 *       sent inline, then by reference with an asset pack, then by reference alone (every asset
 *       already in the child's store), draws the same frame, pixel for pixel.
 *   <li>MISSING recovery: with the parent's mirror made to hold assets the child was never sent, a
 *       file naming them is answered MISSING; the parent resends it inline in a new asset epoch, it
 *       attaches and draws, and the next file by reference keeps parent and child in step.
 * </ol>
 *
 * Authored data only.
 */
public final class WorldStreamObjectReferencesProof {
    private static final int WIDTH = 800, HEIGHT = 500;

    /** The recovery scene's opaque colour, unlike the material scene's. */
    private static final int R = 30, G = 160, B = 220;

    private static long camera;

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var process = new AtomicReference<Process>();
        var backend =
                WorldStreamTransportProof.proofBackend(
                        System.getProperty("vimi.worldProof.backend", "vulkan"));
        var prepared =
                WorldStreamTransport.prepare(
                        output, new WorldStreamTransport.Spec("ab".repeat(32), 1, backend, true));
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
            var reserved = WorldStreamObjectsMaterialProof.reserve(session);
            var resident = WorldStreamTransportProof.produce(reserved).get(8, TimeUnit.SECONDS);
            session.camera(camera());
            var committed = session.commit(1, resident).get(5, TimeUnit.SECONDS);
            long content = committed.contentRevision();
            int[] terrain = frame(session, content);

            // 1. The same scene inline, by reference with a pack, and by reference alone.
            var scene = WorldStreamObjectsMaterialProof.quads();
            session.objectReferencesForProof(false);
            require(attach(session, resident, scene, false), "the inline set attaches");
            int[] inline = frame(session, content);
            require(differing(terrain, inline) > 0, "the inline set draws");
            var plain = session.objectAssets();
            require(plain.referencedFiles() == 0 && plain.sentPacks() == 0, "inline: no pack");

            session.objectReferencesForProof(true);
            require(attach(session, resident, scene, true), "the referenced set attaches");
            int[] packed = frame(session, content);
            var first = session.objectAssets();
            require(
                    first.referencedFiles() == 1 && first.sentPacks() == 1,
                    "the first referenced file sends its assets: " + first);
            require(differing(inline, packed) == 0, "referenced with a pack draws the same pixels");

            require(attach(session, resident, scene, true), "the stored set attaches");
            int[] stored = frame(session, content);
            var second = session.objectAssets();
            require(
                    second.referencedFiles() == 2 && second.sentPacks() == 1,
                    "a second file of the same assets sends none: " + second);
            require(differing(inline, stored) == 0, "referenced from the store draws the same");
            require(second.mirrorResets() == 0, "the mirror agreed with the child throughout");
            long inlineBytes = plain.objectsFileBytes();
            long packedBytes = first.objectsFileBytes() - inlineBytes;
            long storedBytes = second.objectsFileBytes() - first.objectsFileBytes();

            // 2. MISSING: the mirror holds assets the child never had.
            var recolour = single();
            session.assumeAssetsHeldForProof(recolour);
            require(
                    attach(session, resident, recolour, true),
                    "the MISSING set attaches on resend");
            var recovered = session.objectAssets();
            require(
                    recovered.missingReplies() == 1 && recovered.mirrorResets() == 1,
                    "one MISSING reply and one new epoch: " + recovered);
            require(
                    recovered.referencedFiles() == 3 && recovered.sentPacks() == 1,
                    "the resend went inline: " + recovered);
            frame(session, content);
            int centre = WorldStreamObjectsMaterialProof.pixel(lastFrame, WIDTH / 2, HEIGHT / 2);
            int r = Math.abs(WorldStreamObjectsMaterialProof.channel(centre, 16) - R) <= 1 ? 16 : 0;
            require(
                    Math.abs(WorldStreamObjectsMaterialProof.channel(centre, r) - R) <= 1
                            && Math.abs(WorldStreamObjectsMaterialProof.channel(centre, 8) - G) <= 1
                            && Math.abs(WorldStreamObjectsMaterialProof.channel(centre, 16 - r) - B)
                                    <= 1,
                    "the resent set draws its colour: " + Integer.toHexString(centre));
            // In the new epoch the child's store and the mirror start empty together: the next
            // file by reference sends its assets again and both agree on what is held after.
            require(attach(session, resident, recolour, true), "referenced after the resend");
            var after = session.objectAssets();
            require(
                    after.sentPacks() == 2
                            && after.mirrorResets() == 1
                            && after.missingReplies() == 1,
                    "the next file resent its assets and the mirror held: " + after);
            int[] again = frame(session, content);
            require(
                    WorldStreamObjectsMaterialProof.pixel(lastFrame, WIDTH / 2, HEIGHT / 2)
                            == centre,
                    "the same pixel by reference");

            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.exitCode().orElseThrow() != 0 || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
            System.out.println(
                    "WORLD_OBJECT_REFERENCES_PASS backend="
                            + backend
                            + " inlineFile="
                            + inlineBytes
                            + " referencedFile="
                            + packedBytes
                            + "+pack="
                            + first.packBytes()
                            + " storedFile="
                            + storedBytes
                            + " identical=3 missing=1 resetEpochs=1 mirror="
                            + after.mirrorEntries()
                            + "/"
                            + after.mirrorBytes()
                            + " centre="
                            + Integer.toHexString(centre)
                            + " pixels="
                            + again.length);
        } finally {
            session.closeAsync();
            var child = process.get();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private static WorldStreamTransport.Frame lastFrame;

    private static WorldStreamTransport.Camera camera() {
        camera++;
        return new WorldStreamTransport.Camera(
                camera, camera, WIDTH, HEIGHT, WorldStreamObjectsMaterialProof.VIEW);
    }

    /** A new camera revision's frame of {@code content}, as ARGB ints. */
    private static int[] frame(WorldStreamTransport.Session session, long content)
            throws Exception {
        session.camera(camera());
        lastFrame = WorldStreamObjectsMaterialProof.frame(session, content, camera);
        var pixels = new int[lastFrame.pixels().remaining()];
        lastFrame.pixels().duplicate().get(pixels);
        return pixels;
    }

    private static boolean attach(
            WorldStreamTransport.Session session,
            WorldStreamTransport.Resident resident,
            WorldStreamTileWriter.Exterior exterior,
            boolean replace)
            throws Exception {
        var outcome =
                (replace
                                ? session.replaceObjects(resident, exterior)
                                : session.attachObjects(resident, exterior))
                        .get(20, TimeUnit.SECONDS);
        return outcome == WorldStreamTransport.ObjectsOutcome.ATTACHED;
    }

    /** One opaque quad across the frame centre, at full light, in one solid 4 x 4 chain. */
    private static WorldStreamTileWriter.Exterior single() {
        var view = WorldStreamObjectsMaterialProof.VIEW;
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                4,
                4,
                WorldStreamObjectsFileTest.chain(
                        4, 4, WorldStreamObjectsMaterialProof.solid(4, 4, R, G, B, 255)));
        builder.model();
        var vertices = FloatBuffer.allocate(4 * 8);
        double cp = Math.cos(view.pitch()), sp = Math.sin(view.pitch());
        for (int corner = 0; corner < 4; corner++) {
            double right = corner == 1 || corner == 2 ? .9 : -.9;
            double up = corner < 2 ? -.5 : .5, depth = 5;
            // Inverse of TerrainCamera.eye at yaw 0: right = -dy, (up, depth) rotate (dz, dx).
            double dx = -sp * up + cp * depth, dy = -right, dz = cp * up + sp * depth;
            vertices.put((float) dx).put((float) dy).put((float) dz);
            // The normal is the fixed light direction, so the light intensity is exactly 1.
            vertices.put(-0.408248290463863f).put(-0.408248290463863f);
            vertices.put(0.816496580927726f).put(.5f).put(.5f);
        }
        vertices.flip();
        builder.mesh(
                vertices,
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3}),
                new int[] {0, 6, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE});
        builder.placement(
                0, view.x(), view.y(), view.z(), 1, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        return builder.build();
    }

    private static int differing(int[] a, int[] b) {
        if (a.length != b.length) {
            return Math.max(a.length, b.length);
        }
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        System.out.println("OK " + message);
    }

    private WorldStreamObjectReferencesProof() {}
}
