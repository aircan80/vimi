package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
class WorldGlobalSceneFileTest {
    @TempDir Path directory;

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static class Fixture implements WorldGlobalSceneWriter.StructuralRead {
        final WorldGlobalLayout.Counts counts =
                new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 6, 1, 1);
        final float[] vertices = {0, 0, 0, -0f, 0, -1, 0, 0, 1, 0, 0, -2, 0, 0, 1};
        final int[] indices = {0, 1, 2, 2, 1, 0};
        // Profile 3 (#297): the file keeps the source alpha, here 0.
        final byte[] rgba = {(byte) 255, 0, 0, 0};
        final WorldGlobalSceneWriter.Description description;

        Fixture() {
            var raw =
                    ByteBuffer.allocate(64)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(2)
                            .putInt(-1)
                            .putInt(0x80000000);
            raw.position(32);
            for (float value : new float[] {-1, -2, -3, 4, 5, 6}) {
                raw.putFloat(value);
            }
            raw.putShort((short) 8).putShort((short) 0).putShort((short) 0).putShort((short) 1024);
            long[] words = new long[16];
            words[3] = 4;
            words[4] = 0xff000000L;
            words[8] = 10;
            description =
                    new WorldGlobalSceneWriter.Description(
                            249,
                            "7".repeat(64),
                            WorldGlobalHashes.buildLocale(
                                    "wowf", "a".repeat(32), "b".repeat(32), "us", "enUS"),
                            WorldGlobalHashes.RawModf.copyOf(raw.array(), 2, 0xffff_ffffL),
                            List.of(
                                    new WorldGlobalHashes.Source(
                                            1,
                                            WorldGlobalHashes.Role.WDT,
                                            0,
                                            128,
                                            128L,
                                            "a".repeat(32),
                                            "1".repeat(64)),
                                    new WorldGlobalHashes.Source(
                                            2,
                                            WorldGlobalHashes.Role.ROOT,
                                            0,
                                            64,
                                            null,
                                            "b".repeat(32),
                                            "2".repeat(64)),
                                    new WorldGlobalHashes.Source(
                                            3,
                                            WorldGlobalHashes.Role.GROUP,
                                            0,
                                            256,
                                            256L,
                                            "c".repeat(32),
                                            "3".repeat(64)),
                                    new WorldGlobalHashes.Source(
                                            4,
                                            WorldGlobalHashes.Role.IMAGE,
                                            0,
                                            164,
                                            164L,
                                            "d".repeat(32),
                                            "4".repeat(64))),
                            List.of(new WorldGlobalHashes.Material(words)),
                            List.of(
                                    new WorldGlobalHashes.Image(
                                            4,
                                            1,
                                            1,
                                            2,
                                            8,
                                            7,
                                            1,
                                            0,
                                            0,
                                            1,
                                            hash(rgba),
                                            hash(new byte[] {(byte) 255, 0, 0, (byte) 255}))));
        }

        public WorldGlobalLayout.Counts counts() {
            return counts;
        }

        public List<WorldGlobalHashes.Group> groups() {
            return List.of(new WorldGlobalHashes.Group(0, 3, 0, 6, 0, 1, 0xa09));
        }

        public List<WorldGlobalHashes.Batch> batches() {
            return List.of(new WorldGlobalHashes.Batch(0, 0, 0, 3));
        }

        public float vertex(int v, int component) {
            return vertices[v * 5 + component];
        }

        public int index(int index) {
            return indices[index];
        }

        public WorldGlobalSceneWriter.Description description() {
            return description;
        }

        public int imageBytes(int image) {
            return rgba.length;
        }

        public int imageByte(int image, int offset) {
            return rgba[offset] & 255;
        }
    }

    private WorldGlobalSceneWriter.Destination target(Path dir) {
        return WorldGlobalSceneWriter.Destination.create(dir, new Object(), "7".repeat(64));
    }

    @Test
    void canonicalRoundTripUsesActualFileHashesAndPrivateDecodedArrays() throws Exception {
        var fixture = new Fixture();
        var target = target(directory);
        try (var source = WorldGlobalSceneWriter.writeUnadvertised(target, fixture, () -> false)) {
            var expected = source.expected();
            assertEquals(fixture.counts, expected.counts());
            assertEquals(Files.size(target.path()), expected.length());
            byte[] actual = Files.readAllBytes(target.path());
            var b = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(
                    "VIMIGWS1",
                    new String(actual, 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(0x80000000, b.getInt(368));
            var crc = new CRC32C();
            crc.update(actual, 1024, actual.length - 1024);
            assertEquals(crc.getValue(), Integer.toUnsignedLong(b.getInt(424)));
            byte[] masked = actual.clone();
            Arrays.fill(masked, 160, 192, (byte) 0);
            assertEquals(hash(masked), expected.identity().fileSha256());
            try (var decoded =
                    GpuGlobalWmoSceneFile.read(target.path(), expected.input(), () -> false)) {
                assertEquals(fixture.description.recipe(), decoded.description().recipe());
                assertEquals(fixture.counts, decoded.counts());
                for (int vertex = 0; vertex < 3; vertex++) {
                    for (int component = 0; component < 5; component++) {
                        assertEquals(
                                Float.floatToRawIntBits(fixture.vertex(vertex, component)),
                                Float.floatToRawIntBits(decoded.vertex(vertex, component)));
                    }
                }
                for (int index = 0; index < 6; index++) {
                    assertEquals(fixture.index(index), decoded.index(index));
                }
                for (int n = 0; n < 4; n++) {
                    assertEquals(fixture.imageByte(0, n), decoded.imageByte(0, n));
                }
                assertEquals(1, decoded.description().images().getFirst().nonopaque());
                assertEquals(0, decoded.description().images().getFirst().alphaMax());
                decoded.close();
                assertThrows(IllegalStateException.class, () -> decoded.vertex(0, 0));
            }
            var moved = source.move();
            source.close();
            assertTrue(Files.exists(target.path()));
            moved.close();
            assertFalse(Files.exists(target.path()));
        }
    }

    private static WorldGlobalSceneWriter.InputExpectation resign(
            Path path, byte[] bytes, WorldGlobalStatus.Identity old) throws Exception {
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Arrays.fill(bytes, 160, 192, (byte) 0);
        b.putInt(428, 0);
        var crc = new CRC32C();
        crc.update(bytes, 1024, bytes.length - 1024);
        b.putInt(424, (int) crc.getValue());
        b.putInt(428, (int) WorldGlobalHashes.headerCrc(Arrays.copyOf(bytes, 1024)));
        String sha = hash(bytes);
        b.position(160).put(HexFormat.of().parseHex(sha));
        Files.write(path, bytes);
        return new WorldGlobalSceneWriter.InputExpectation(
                new WorldGlobalStatus.Identity(old.nonce(), old.scope(), sha), bytes.length);
    }

    @Test
    void validChecksumsCannotHideNoncanonicalHeaderOrDescriptorSubstitutions() throws Exception {
        assertEquals(3, WorldGlobalLayout.PROFILE);
        for (int mode = 0; mode < 12; mode++) {
            var dir = Files.createDirectory(directory.resolve("bad-" + mode));
            var target = target(dir);
            try (var source =
                    WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false)) {
                byte[] bytes = Files.readAllBytes(target.path());
                var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                switch (mode) {
                    case 0 -> b.putInt(36, 1);
                    case 1 -> b.putInt(244, Integer.MAX_VALUE);
                    case 2 -> b.putLong(264, Long.MIN_VALUE);
                    case 3 -> bytes[432] = 1;
                    case 4 -> bytes[480] = 1;
                    case 5 -> b.putInt(312, 1025);
                    case 6 -> b.putInt(1024 + 4 * 64 + 64 + 24, 1);
                    case 7 -> b.putInt(1024 + 64 + 12, 1);
                    case 8 -> b.putInt(1424 + 16, 1561);
                    case 9 -> b.putInt(344, 5);
                    // The format version word: the first profile's 1 and a zero both refuse,
                    // even with repaired checksums (version 2 is current).
                    case 10 -> b.putInt(8, 1);
                    case 11 -> b.putInt(8, 0);
                    default -> throw new AssertionError();
                }
                var expected = resign(target.path(), bytes, source.expected().identity());
                assertThrows(
                        IOException.class,
                        () -> GpuGlobalWmoSceneFile.read(target.path(), expected, () -> false),
                        "variant " + mode);
            }
        }
    }

    @Test
    void payloadIndexAlphaAndNativeBoundsAreCheckedEvenWithRepairedIntegrity() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var dir = Files.createDirectory(directory.resolve("payload-" + mode));
            var target = target(dir);
            try (var source =
                    WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false)) {
                byte[] bytes = Files.readAllBytes(target.path());
                var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                var offsets = source.expected().counts().offsets();
                switch (mode) {
                    case 0 -> b.putFloat(offsets.vertices(), -0f);
                    case 1 -> b.putShort(offsets.indices(), (short) 3);
                    // A changed source alpha no longer matches the image digest (#297).
                    case 2 -> bytes[bytes.length - 1] = 1;
                    case 3 -> b.putFloat(288, -2);
                    default -> throw new AssertionError();
                }
                var expected = resign(target.path(), bytes, source.expected().identity());
                assertThrows(
                        IOException.class,
                        () -> GpuGlobalWmoSceneFile.read(target.path(), expected, () -> false));
            }
        }
    }

    @Test
    void partialWriterFailureNeverDeletesPreexistingInputAndCleansItsOwnFile() throws Exception {
        var target = target(directory);
        byte[] prior = {1, 2, 3};
        Files.write(target.path(), prior);
        assertThrows(
                IOException.class,
                () -> WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false));
        assertEquals(3, Files.size(target.path()));
        Files.delete(target.path());
        var fixture =
                new Fixture() {
                    @Override
                    public int imageByte(int image, int offset) {
                        if (offset == 2) {
                            throw new AssertionError("Actual writer callback failed");
                        }
                        return super.imageByte(image, offset);
                    }
                };
        assertThrows(
                AssertionError.class,
                () -> WorldGlobalSceneWriter.writeUnadvertised(target, fixture, () -> false));
        assertFalse(Files.exists(target.path()));
        assertThrows(
                CancellationException.class,
                () ->
                        WorldGlobalSceneWriter.writeUnadvertised(
                                target, new Fixture(), () -> Files.exists(target.path())));
        assertFalse(Files.exists(target.path()));
    }

    @Test
    void inputExtentSymlinkCancellationAndWrongExpectedIdentityDoNotDecode() throws Exception {
        var target = target(directory);
        try (var source =
                WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false)) {
            var wrong =
                    new WorldGlobalSceneWriter.InputExpectation(
                            new WorldGlobalStatus.Identity(
                                    "0".repeat(32),
                                    target.scope(),
                                    source.expected().identity().fileSha256()),
                            source.expected().length());
            assertThrows(
                    IOException.class,
                    () -> GpuGlobalWmoSceneFile.read(target.path(), wrong, () -> false));
            assertThrows(
                    CancellationException.class,
                    () ->
                            GpuGlobalWmoSceneFile.read(
                                    target.path(), source.expected().input(), () -> true));
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                Path link = directory.resolve("link.wgs");
                Files.createSymbolicLink(link, target.path());
                assertThrows(
                        IOException.class,
                        () ->
                                GpuGlobalWmoSceneFile.read(
                                        link, source.expected().input(), () -> false));
            }
            byte[] bytes = Files.readAllBytes(target.path());
            Files.write(target.path(), Arrays.copyOf(bytes, bytes.length + 1));
            assertThrows(
                    IOException.class,
                    () ->
                            GpuGlobalWmoSceneFile.read(
                                    target.path(), source.expected().input(), () -> false));
        }
    }

    @Test
    void exactMaximumCanonicalFileRoundTripsWithoutProviderPayloadArrays() throws Exception {
        var maximum = new WorldGlobalLayout.Counts(24, 8, 64, 18, 32768, 98304, 32768, 1179648);
        var sources = new java.util.ArrayList<WorldGlobalHashes.Source>();
        sources.add(
                new WorldGlobalHashes.Source(
                        1,
                        WorldGlobalHashes.Role.WDT,
                        0,
                        128,
                        128L,
                        "a".repeat(32),
                        "1".repeat(64)));
        sources.add(
                new WorldGlobalHashes.Source(
                        2,
                        WorldGlobalHashes.Role.ROOT,
                        0,
                        64,
                        null,
                        "b".repeat(32),
                        "2".repeat(64)));
        for (int n = 0; n < 8; n++) {
            long id = 3 + n;
            sources.add(
                    new WorldGlobalHashes.Source(
                            id,
                            WorldGlobalHashes.Role.GROUP,
                            n,
                            256,
                            256L,
                            String.format("%032x", id),
                            String.format("%064x", id)));
        }
        byte[] oneImage = new byte[262144];
        for (int n = 0; n < oneImage.length; n += 4) {
            oneImage[n] = (byte) 255;
            oneImage[n + 3] = (byte) 255;
        }
        String opaque = hash(oneImage);
        var images = new java.util.ArrayList<WorldGlobalHashes.Image>();
        for (int n = 0; n < 18; n++) {
            long id = 100 + n;
            sources.add(
                    new WorldGlobalHashes.Source(
                            id,
                            WorldGlobalHashes.Role.IMAGE,
                            n,
                            44876,
                            44876L,
                            String.format("%032x", id),
                            String.format("%064x", id)));
            images.add(
                    new WorldGlobalHashes.Image(
                            id, 256, 256, 1, 0, 0, 9, 255, 255, 0, opaque, opaque));
        }
        var materials = new java.util.ArrayList<WorldGlobalHashes.Material>();
        for (int n = 0; n < 24; n++) {
            long[] words = new long[16];
            words[3] = 100 + n % 18;
            materials.add(new WorldGlobalHashes.Material(words));
        }
        var original = new Fixture();
        var data =
                new WorldGlobalSceneWriter.Description(
                        249,
                        original.description.scope(),
                        original.description.buildLocale(),
                        original.description.modf(),
                        sources,
                        materials,
                        images);
        var groups = new java.util.ArrayList<WorldGlobalHashes.Group>();
        var batches = new java.util.ArrayList<WorldGlobalHashes.Batch>();
        for (int n = 0; n < 8; n++) {
            groups.add(
                    new WorldGlobalHashes.Group(n * 4096, 4096, n * 12288, 12288, n * 8, 8, 0xa09));
            for (int k = 0; k < 8; k++) {
                batches.add(new WorldGlobalHashes.Batch(n, 0, n * 12288 + k * 1536, 1536));
            }
        }
        WorldGlobalSceneWriter.StructuralRead procedural =
                new WorldGlobalSceneWriter.StructuralRead() {
                    public WorldGlobalLayout.Counts counts() {
                        return maximum;
                    }

                    public WorldGlobalSceneWriter.Description description() {
                        return data;
                    }

                    public List<WorldGlobalHashes.Group> groups() {
                        return groups;
                    }

                    public List<WorldGlobalHashes.Batch> batches() {
                        return batches;
                    }

                    public float vertex(int vertex, int component) {
                        return component == 0
                                ? (vertex % 100) / 100f
                                : component == 3 ? (vertex % 33 - 16) / 2f : 0;
                    }

                    public int index(int ordinal) {
                        return ordinal / 12288 * 4096 + ordinal % 12288 % 4096;
                    }

                    public int imageBytes(int image) {
                        return 262144;
                    }

                    public int imageByte(int image, int offset) {
                        return offset % 4 == 0 || offset % 4 == 3 ? 255 : 0;
                    }
                };
        var target = target(directory);
        try (var source =
                        WorldGlobalSceneWriter.writeUnadvertised(target, procedural, () -> false);
                var decoded =
                        GpuGlobalWmoSceneFile.read(
                                target.path(), source.expected().input(), () -> false)) {
            // The first profile's maximum, with 96-byte image records and 32-bit indices.
            assertEquals(5_577_600 + 18 * 32 + 98304 * 2, Files.size(target.path()));
            assertEquals(maximum, decoded.counts());
            assertEquals(32767, decoded.index(98303));
            assertEquals(255, decoded.imageByte(17, 262143));
            assertEquals(18, decoded.description().images().size());
            assertEquals(
                    WorldGlobalHashes.mesh(procedural, () -> false),
                    WorldGlobalHashes.mesh(decoded, () -> false));
        }
    }

    @Test
    void readerUsesOneOpenedHandleRatherThanReopeningReplacedPath() throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        var target = target(directory);
        try (var source =
                WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false)) {
            var calls = new AtomicInteger();
            var replaced = new java.util.concurrent.atomic.AtomicBoolean();
            Path held = directory.resolve("actual-opened.wgs");
            try (var decoded =
                    GpuGlobalWmoSceneFile.read(
                            target.path(),
                            source.expected().input(),
                            () -> {
                                if (calls.incrementAndGet() == 2) {
                                    try {
                                        Files.move(target.path(), held);
                                        Files.write(target.path(), new byte[] {1, 2, 3});
                                        replaced.set(true);
                                    } catch (IOException failure) {
                                        throw new java.io.UncheckedIOException(failure);
                                    }
                                }
                                return false;
                            })) {
                assertTrue(replaced.get());
                assertEquals(3, decoded.counts().vertices());
                assertEquals(255, decoded.imageByte(0, 0));
            }
            assertEquals(3, Files.size(target.path()));
            Files.delete(target.path());
            Files.move(held, target.path());
        }
    }

    @Test
    void cancellationDuringSecondPassAndFinalMeshGateNeverTransfersDecodedOwner() throws Exception {
        var target = target(directory);
        try (var source =
                WorldGlobalSceneWriter.writeUnadvertised(target, new Fixture(), () -> false)) {
            for (String phase : List.of("cursor", "mesh")) {
                var observed = new java.util.concurrent.atomic.AtomicBoolean();
                assertThrows(
                        CancellationException.class,
                        () ->
                                GpuGlobalWmoSceneFile.read(
                                        target.path(),
                                        source.expected().input(),
                                        () -> {
                                            boolean at =
                                                    StackWalker.getInstance()
                                                            .walk(
                                                                    frames ->
                                                                            frames.anyMatch(
                                                                                    frame ->
                                                                                            phase
                                                                                                            .equals(
                                                                                                                    "cursor")
                                                                                                    ? frame.getClassName()
                                                                                                                    .endsWith(
                                                                                                                            "WorldGlobalFileAccess$Cursor")
                                                                                                            && frame.getMethodName()
                                                                                                                    .equals(
                                                                                                                            "refill")
                                                                                                    : frame.getClassName()
                                                                                                                    .equals(
                                                                                                                            WorldGlobalHashes
                                                                                                                                    .class
                                                                                                                                    .getName())
                                                                                                            && frame.getMethodName()
                                                                                                                    .equals(
                                                                                                                            "mesh")));
                                            if (at) {
                                                observed.set(true);
                                            }
                                            return at;
                                        }));
                assertTrue(observed.get());
                try (var fresh =
                        GpuGlobalWmoSceneFile.read(
                                target.path(), source.expected().input(), () -> false)) {
                    assertNotNull(fresh);
                }
            }
        }
    }
}
