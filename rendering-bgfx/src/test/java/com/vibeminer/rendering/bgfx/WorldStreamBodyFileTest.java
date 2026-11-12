package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WorldStreamBodyFileTest {
    @Test
    void aBodyRoundTripsAndKeepsItsAnimations() throws IOException {
        var body = WorldStreamBodyProof.authored();
        byte[] bytes = WorldStreamBodyFile.encode(body);
        var copy = WorldStreamBodyFile.decode(bytes);
        assertArrayEquals(body.vertices(), copy.vertices());
        assertArrayEquals(body.indices(), copy.indices());
        assertArrayEquals(body.ranges(), copy.ranges());
        assertArrayEquals(body.blends(), copy.blends());
        assertArrayEquals(body.sources(), copy.sources());
        assertArrayEquals(body.sequences(), copy.sequences());
        assertEquals(body.scale(), copy.scale());
        assertArrayEquals(body.images().getFirst().rgba(), copy.images().getFirst().rgba());
        assertEquals(7f, copy.animation(5).moveSpeed());
        float[] expected = new float[body.vertexCount() * 3], actual = new float[expected.length];
        body.animation(4).skin(300, expected, null);
        copy.animation(4).skin(300, actual, null);
        assertArrayEquals(expected, actual);
        assertEquals(null, copy.animation(42));
    }

    @Test
    void aCorruptTruncatedOrOversizedFileIsRefused() throws IOException {
        byte[] good = WorldStreamBodyFile.encode(WorldStreamBodyProof.authored());
        byte[] magic = good.clone();
        magic[0] ^= 1;
        assertThrows(IOException.class, () -> WorldStreamBodyFile.decode(magic));
        for (int cut : new int[] {4, 20, good.length / 2, good.length - 1}) {
            byte[] truncated = java.util.Arrays.copyOf(good, cut);
            assertThrows(
                    IOException.class, () -> WorldStreamBodyFile.decode(truncated), "cut " + cut);
        }
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> WorldStreamBodyFile.decode(trailing));
        byte[] vertices = good.clone();
        ByteBuffer.wrap(vertices).putInt(4, WorldStreamBodyFile.MAX_VERTICES + 1);
        assertThrows(IOException.class, () -> WorldStreamBodyFile.decode(vertices));
        // An index past the vertices: the first index sits after the vertex floats and the count.
        int vertexCount = ByteBuffer.wrap(good).getInt(4);
        byte[] index = good.clone();
        ByteBuffer.wrap(index).putShort(8 + vertexCount * 32 + 4, (short) vertexCount);
        assertThrows(IOException.class, () -> WorldStreamBodyFile.decode(index));
        assertThrows(IOException.class, () -> WorldStreamBodyFile.decode(new byte[8]));
    }

    @Test
    void aBodyRefusesInconsistentParts() {
        var body = WorldStreamBodyProof.authored();
        int[] sources = body.sources();
        sources[0] = body.animation(0).vertexCount();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamBodyFile.Body(
                                body.vertices(),
                                body.indices(),
                                body.ranges(),
                                body.blends(),
                                body.images(),
                                sources,
                                1,
                                body.sequences(),
                                body.animations()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamBodyFile.Body(
                                body.vertices(),
                                body.indices(),
                                new int[] {0, 3, 1},
                                body.blends(),
                                body.images(),
                                body.sources(),
                                1,
                                body.sequences(),
                                body.animations()),
                "a range naming a missing image");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamBodyFile.Body(
                                body.vertices(),
                                body.indices(),
                                body.ranges(),
                                body.blends(),
                                body.images(),
                                body.sources(),
                                1,
                                new int[] {0, 4, 4},
                                body.animations()),
                "a repeated sequence");
    }

    @Test
    void gatheringWritesTheSkinnedSourceVertexInTheObjectLayout() {
        float[] model = {
            9, 9, 9, 0, 0, 1, .25f, .75f, //
            9, 9, 9, 0, 1, 0, .5f, .5f
        };
        int[] sources = {1, 0};
        float[] positions = {1, 2, 3, 4, 5, 6}, normals = {1, 0, 0, 0, 0, -1};
        var out =
                ByteBuffer.allocate(2 * WorldStreamPackedObjects.GPU_VERTEX_BYTES)
                        .order(ByteOrder.nativeOrder());
        WorldStreamBodyDraw.gather(model, sources, positions, normals, out);
        assertEquals(4, out.getFloat(0));
        assertEquals(6, out.getFloat(8));
        assertEquals(-1, out.getFloat(20), "the skinned normal");
        assertEquals(.25f, out.getFloat(24));
        assertEquals(.75f, out.getFloat(28));
        assertEquals(0, out.getInt(32), "no vertex colour");
        int second = WorldStreamPackedObjects.GPU_VERTEX_BYTES;
        assertEquals(1, out.getFloat(second));
        WorldStreamBodyDraw.gather(model, sources, positions, null, out);
        assertEquals(1, out.getFloat(20), "the model's own normal without skinned ones");
    }

    @Test
    void theBodyIsChargedItsHandlesAndBytes() {
        var body = WorldStreamBodyProof.authored();
        assertEquals(1, WorldStreamBodyDraw.handles(body));
        assertEquals(
                (long) body.vertexCount() * WorldStreamPackedObjects.GPU_VERTEX_BYTES
                        + body.indices().length * 2L
                        + body.images().getFirst().rgba().length,
                WorldStreamBodyDraw.gpuBytes(body));
    }

    @Test
    void theChildRefusesABodyFileThatIsNotTheOneAnnounced(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        byte[] good = WorldStreamBodyFile.encode(WorldStreamBodyProof.authored());
        var file = directory.resolve(WorldStreamControl.BodyModel.BASENAME);
        java.nio.file.Files.write(file, good);
        var right =
                new WorldStreamControl.BodyModel(
                        new WorldStreamState.Nonce(5, 6), good.length, sha(good));
        assertEquals(
                48, WorldStreamSession.decodeBody(directory, right, () -> false).vertexCount());
        var wrongDigest =
                new WorldStreamControl.BodyModel(right.nonce(), good.length, "0".repeat(64));
        assertThrows(
                IOException.class,
                () -> WorldStreamSession.decodeBody(directory, wrongDigest, () -> false));
        var wrongLength =
                new WorldStreamControl.BodyModel(right.nonce(), good.length - 1, sha(good));
        assertThrows(
                IOException.class,
                () -> WorldStreamSession.decodeBody(directory, wrongLength, () -> false));
        // A corrupt file with a matching digest still fails the body file's own checks.
        byte[] corrupt = good.clone();
        ByteBuffer.wrap(corrupt).putInt(4, 0);
        java.nio.file.Files.write(file, corrupt);
        var announced =
                new WorldStreamControl.BodyModel(right.nonce(), corrupt.length, sha(corrupt));
        assertThrows(
                IOException.class,
                () -> WorldStreamSession.decodeBody(directory, announced, () -> false));
        java.nio.file.Files.delete(file);
        assertThrows(
                IOException.class,
                () -> WorldStreamSession.decodeBody(directory, announced, () -> false));
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
