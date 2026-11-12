package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Idle animation of World object models (#384): phases, clock, gathering and packing. */
final class WorldObjectAnimationTest {
    /**
     * An 800 ms idle over the four vertices of {@link WorldStreamObjectsFileTest#small}'s quad: the
     * far edge (vertices 2 and 3) swings about X on its own bone, the near edge stays still.
     */
    static PreviewAnimation swing() {
        var exterior = WorldStreamObjectsFileTest.small();
        float[] model = exterior.data().vertices;
        float[] rest = new float[12], normals = new float[12];
        int[] bones = new int[16], weights = new int[16];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(model, v * 8, rest, v * 3, 3);
            System.arraycopy(model, v * 8 + 3, normals, v * 3, 3);
            bones[v * 4] = v < 2 ? 0 : 1;
            weights[v * 4] = 255;
        }
        var mesh =
                new PreviewMesh(
                        rest, new int[] {0, 1, 2, 2, 1, 3}, List.of(new PreviewMesh.Part(0, 0, 6)));
        float swing = (float) Math.sin(.4), w = (float) Math.sqrt(1 - swing * swing);
        var root = new PreviewAnimation.Bone(-1, 0, 0, 0, null, null, null, false);
        var edge =
                new PreviewAnimation.Bone(
                        0,
                        0,
                        0,
                        3,
                        null,
                        new PreviewAnimation.Track(
                                PreviewAnimation.Interpolation.LINEAR,
                                -1,
                                4,
                                new int[] {0, 400, 800},
                                new float[] {0, 0, 0, 1, swing, 0, 0, w, 0, 0, 0, 1}),
                        null,
                        false);
        return new PreviewAnimation(
                "Stand", 800, new long[0], List.of(root, edge), mesh, normals, bones, weights, 0);
    }

    static byte[] bytes(PreviewAnimation animation) throws IOException {
        var out = new ByteArrayOutputStream();
        animation.write(new DataOutputStream(out));
        return out.toByteArray();
    }

    @Test
    void aPlacementKeepsItsPhaseAndACrowdSpreadsOverEveryPhase() {
        assertEquals(
                WorldObjectAnimation.phase(-8833.5, 622.25),
                WorldObjectAnimation.phase(-8833.5, 622.25));
        var seen = new int[WorldObjectAnimation.PHASES];
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                int phase = WorldObjectAnimation.phase(-9000 + x * 1.5, 400 + y * 2.25);
                assertTrue(phase >= 0 && phase < WorldObjectAnimation.PHASES);
                seen[phase]++;
            }
        }
        for (int count : seen) {
            // 400 neighbours over four phases: none is empty or takes most of them.
            assertTrue(count > 50 && count < 150, java.util.Arrays.toString(seen));
        }
    }

    @Test
    void theClockStepsAndEachPhaseIsOffsetByItsShareOfTheLoop() {
        long step = WorldObjectAnimation.STEP_MILLIS;
        assertEquals(0, WorldObjectAnimation.millis(step - 1, 800, 0));
        assertEquals(step, WorldObjectAnimation.millis(step, 800, 0));
        assertEquals(200, WorldObjectAnimation.millis(0, 800, 1));
        assertEquals(600, WorldObjectAnimation.millis(0, 800, 3));
        // Loops: 840 ms into an 800 ms idle is 40 ms in; phase 3 wraps too.
        assertEquals(40, WorldObjectAnimation.millis(840, 800, 0));
        assertEquals(40 + 600, WorldObjectAnimation.millis(840, 800, 3));
        assertEquals(
                240,
                WorldObjectAnimation.millis(840, 800, 1)
                        - WorldObjectAnimation.millis(840, 800, 0)
                        + 40);
        assertEquals(0, WorldObjectAnimation.millis(1234, 0, 2));
    }

    @Test
    void gatheringWritesSkinnedPositionsAndKeepsTextureCoordinatesAndColours() {
        var animation = swing();
        var exterior = WorldStreamObjectsFileTest.small();
        var data = exterior.data();
        var packed =
                WorldStreamPackedObjects.animation(
                        data, 0, new int[] {0, 1, 2, 3}, uncheckedBytes(animation));
        assertNotNull(packed);
        var skinner = animation.skinner();
        float[] positions = new float[12], normals = new float[12];
        var out = ByteBuffer.allocate(packed.vertices.length).order(ByteOrder.nativeOrder());
        out.put(packed.vertices).flip();
        var rest = ByteBuffer.wrap(packed.vertices).order(ByteOrder.nativeOrder());
        skinner.skin(400, positions, normals);
        WorldObjectAnimation.gather(rest, packed.sources, positions, normals, out);
        int stride = WorldStreamPackedObjects.GPU_VERTEX_BYTES;
        for (int v = 0; v < 4; v++) {
            assertEquals(positions[v * 3 + 2], out.getFloat(v * stride + 8), 1e-6f);
            // Texture coordinates and colours stay as uploaded.
            assertEquals(rest.getFloat(v * stride + 24), out.getFloat(v * stride + 24));
            assertEquals(rest.getFloat(v * stride + 28), out.getFloat(v * stride + 28));
            assertEquals(rest.getInt(v * stride + 32), out.getInt(v * stride + 32));
        }
        // At 400 ms the far edge has swung; the near edge has not moved.
        assertEquals(rest.getFloat(8), out.getFloat(8), 1e-6f);
        assertNotEquals(rest.getFloat(2 * stride + 8), out.getFloat(2 * stride + 8), 1e-3f);
    }

    @Test
    void packingDropsAnAnimationThatDoesNotFitItsModel() throws IOException {
        var data = WorldStreamObjectsFileTest.small().data();
        byte[] bytes = bytes(swing());
        var fit = WorldStreamPackedObjects.animation(data, 0, new int[] {3, 2, 1, 0}, bytes);
        assertNotNull(fit);
        assertArrayEquals(new int[] {0}, fit.meshOffsets);
        assertEquals(4 * WorldStreamPackedObjects.GPU_VERTEX_BYTES, fit.vertices.length);
        // A source outside the rest surface, or bytes that are not an animation.
        assertNull(WorldStreamPackedObjects.animation(data, 0, new int[] {0, 1, 2, 4}, bytes));
        assertNull(
                WorldStreamPackedObjects.animation(
                        data, 0, new int[] {0, 1, 2, 3}, new byte[] {1, 2, 3}));
    }

    @Test
    void anAnimationMustNameEveryVertexOfItsModel() throws IOException {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, ByteBuffer.allocate(16)));
        builder.model();
        float[] vertices = new float[4 * 8];
        builder.mesh(
                java.nio.FloatBuffer.wrap(vertices),
                java.nio.IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        byte[] bytes = bytes(swing());
        assertThrows(IllegalArgumentException.class, () -> builder.animate(1, new int[4], bytes));
        builder.animate(0, new int[3], bytes);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    private static byte[] uncheckedBytes(PreviewAnimation animation) {
        try {
            return bytes(animation);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
