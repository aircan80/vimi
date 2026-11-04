package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldBasicWmoTest {
    private static final WorldService.MapInfo MAP = new WorldService.MapInfo(7, "A", "B", 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 4, 0, 3, 0, 0);
    private static final WorldBasicWmo.Selection SELECTION =
            new WorldBasicWmo.Selection(7, TILE, 0, 77);

    private static ByteBuffer buffer(int count) {
        return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float f : values) {
            b.putFloat(f);
        }
        return b.array();
    }

    private static byte[] chunk(String tag, byte[] bytes) {
        int n = 0;
        for (char c : tag.toCharArray()) {
            n = n << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(n).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] join(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (var p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    private static byte[] version(int v) {
        return chunk("MVER", buffer(4).putInt(v).array());
    }

    private static byte[] object(long id, int flags, float heading) {
        var b = buffer(64);
        b.putInt((int) id).putInt(77).put(floats(100, 20, 300)).put(floats(0, heading, 0));
        b.put(floats(-10, -10, -10, 10, 10, 10))
                .putShort((short) flags)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 2048);
        return b.array();
    }

    private static byte[] root(int flags) {
        var h = buffer(64);
        h.putInt(1)
                .putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0xff0b0b0b)
                .putInt(123);
        h.put(floats(-1, -1, -1, 2, 2, 2)).putShort((short) flags).putShort((short) 0);
        var m = buffer(64);
        m.putInt(12, 10).putInt(16, 0xff000000).putInt(28, 0xff7f7f7f).putInt(32, 10);
        return join(
                version(17),
                chunk("MOHD", h.array()),
                chunk("MOMT", m.array()),
                chunk("GFID", buffer(4).putInt(6).array()));
    }

    private static byte[] group(byte[]... extras) {
        var h = buffer(68);
        h.putInt(0).putInt(0).putInt(9).put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) 1);
        var batch = buffer(24);
        batch.putInt(12, 0).putShort(16, (short) 3).putShort(20, (short) 2);
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 2, 0)),
                                chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                chunk("MOTV", floats(0, 0, 1, 0, 0, 1)),
                                chunk(
                                        "MOVI",
                                        buffer(6)
                                                .putShort((short) 0)
                                                .putShort((short) 1)
                                                .putShort((short) 2)
                                                .array()),
                                chunk("MOPY", new byte[] {1, (byte) 255}),
                                chunk("MOBA", batch.array()),
                                join(extras))));
    }

    private static byte[] image() {
        var b = buffer(156);
        b.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 1)
                .putInt(4)
                .putInt(4);
        b.putInt(20, 148).putInt(84, 8).position(148);
        b.putShort((short) 0xf800).putShort((short) 0).putInt(0);
        return b.array();
    }

    private static final class Fixture implements AutoCloseable {
        final WorldTerrainTextureSourceTest.Files files;
        final AuthoredNativeMap nativeMap;
        final WorldStreamScopes engine;
        final WorldStreamScopes.Scope scope;
        final TerrainScene scene;

        Fixture() {
            this(new WorldTerrainTextureSourceTest.Files());
        }

        Fixture(WorldTerrainTextureSourceTest.Files files) {
            this.files = files;
            int offset = 12 + 40 + 8 + 32768 + 8 + (7 * 64 + 19) * 32;
            ByteBuffer.wrap(files.sources.get(1L))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(offset + 4, 4);
            files.sources.put(4L, join(version(18), chunk("MODF", object(5, 12, 90))));
            files.sources.put(5L, root(0));
            files.sources.put(6L, group());
            files.sources.put(10L, image());
            nativeMap = new AuthoredNativeMap(files.files());
            engine = new WorldStreamScopes(nativeMap.tables(), files.files());
            scope = engine.create(MAP, Cancellation.NONE);
            scene =
                    new TerrainScene(
                            List.of(
                                    new TerrainScene.Tile(
                                            7,
                                            19,
                                            WorldFormats.adt(files.sources.get(2L), () -> false))));
        }

        <R extends AutoCloseable> R run(WorldBasicWmo.Output<R> callback) {
            return WorldBasicWmo.prepare(
                    engine, scope, SELECTION, scene, callback, Cancellation.NONE);
        }

        public void close() {
            engine.closeAsync().join();
            nativeMap.close();
        }
    }

    @Test
    void actualNativeMapAndRecordedChainQualifyWithExpiringReadonlyBorrow() throws Exception {
        try (var f = new Fixture()) {
            var captured = new WorldBasicWmo.Borrow[1];
            var closed = new AtomicBoolean();
            AutoCloseable result =
                    f.run(
                            (borrow, scene, token) -> {
                                captured[0] = borrow;
                                assertEquals(3, borrow.indices().remaining());
                                assertEquals(9, borrow.positions().remaining());
                                assertEquals(1, borrow.imageCount());
                                assertEquals(10, borrow.imageId(0));
                                assertEquals(255, borrow.rgba(0).get(0) & 255);
                                assertEquals(0, borrow.rgba(0).get(1));
                                assertTrue(borrow.rgba(0).isReadOnly());
                                assertTrue(borrow.normals().isReadOnly());
                                assertEquals(6, borrow.bindings().size());
                                assertEquals(7, borrow.metadata().mapId());
                                assertEquals(7, scene.x());
                                assertEquals(19, scene.y());
                                // Independent asymmetric 90-degree, scale2 ADT matrix oracle.
                                var t = borrow.transform();
                                double c = 51200.0 / 3.0;
                                assertEquals(c - 300 + 4, t.x(1, 2), 1e-10);
                                assertEquals(c - 100 - 2, t.y(1, 2), 1e-10);
                                assertEquals(26, t.z(3), 0);
                                assertEquals(1, t.normalX(0, 1), 1e-15);
                                assertEquals(-1, t.normalY(1, 0), 1e-15);
                                return (AutoCloseable) () -> closed.set(true);
                            });
            assertFalse(closed.get());
            assertThrows(IllegalArgumentException.class, () -> captured[0].positions());
            result.close();
            assertTrue(closed.get());
        }
    }

    @Test
    void unsupportedSourcesCannotReachPositiveCallback() {
        for (int mode = 0; mode < 7; mode++) {
            try (var f = new Fixture()) {
                switch (mode) {
                    case 0 -> f.files.sources.put(5L, root(1));
                    case 1 -> f.files.sources.put(6L, group(chunk("MOCV", new byte[12])));
                    case 2 ->
                            f.files.sources.put(
                                    4L, join(version(18), chunk("MODF", object(5, 8, 90))));
                    case 3 ->
                            f.files.sources.put(
                                    4L,
                                    join(
                                            version(18),
                                            chunk(
                                                    "MODF",
                                                    join(object(5, 12, 90), object(5, 12, 90)))));
                    case 4 ->
                            f.files.sources.put(
                                    4L, join(version(18), chunk("MODF", object(5, 12, 90))));
                    case 5 -> f.files.sources.get(10L)[11] = 2;
                    case 6 -> f.files.sources.put(5L, join(root(0), chunk("XTRA", new byte[0])));
                    default -> throw new AssertionError();
                }
                var selection = mode == 4 ? new WorldBasicWmo.Selection(7, TILE, 0, 88) : SELECTION;
                var entered = new AtomicBoolean();
                assertThrows(
                        RuntimeException.class,
                        () ->
                                WorldBasicWmo.prepare(
                                        f.engine,
                                        f.scope,
                                        selection,
                                        f.scene,
                                        (borrow, scene, token) -> {
                                            entered.set(true);
                                            return (AutoCloseable) () -> {};
                                        },
                                        Cancellation.NONE));
                assertFalse(entered.get(), "mode " + mode);
            }
        }
    }

    @Test
    void changedSourceDuringOutputClosesUnadvertisedResultAndExpiresBorrow() {
        try (var f = new Fixture()) {
            var closed = new AtomicBoolean();
            var captured = new WorldBasicWmo.Borrow[1];
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.run(
                                    (borrow, scene, token) -> {
                                        captured[0] = borrow;
                                        f.files.changedId = 5;
                                        f.files.changeAfterRead = true;
                                        return (AutoCloseable) () -> closed.set(true);
                                    }));
            assertTrue(closed.get());
            assertThrows(IllegalArgumentException.class, () -> captured[0].indices());
        }
    }

    @Test
    void callbackExceptionExpiresBorrowAndReleasesInputAdmission() throws Exception {
        try (var f = new Fixture()) {
            var captured = new WorldBasicWmo.Borrow[1];
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.run(
                                    (borrow, scene, token) -> {
                                        captured[0] = borrow;
                                        throw new IllegalStateException(
                                                "authored callback failure");
                                    }));
            assertThrows(IllegalArgumentException.class, () -> captured[0].rgba(0));
            f.run((borrow, scene, token) -> (AutoCloseable) () -> {}).close();
        }
    }

    private static void patch(byte[] bytes, String name, int field, int value) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= bytes.length - 8; i += 2) {
            if (b.getInt(i) == tag) {
                b.putInt(i + 8 + field, value);
                return;
            }
        }
        throw new AssertionError("Missing authored chunk " + name);
    }

    @Test
    void materialBatchNormalAndUnknownChunkSemanticsAreExplicitRefusals() {
        for (int mode = 0; mode < 9; mode++) {
            try (var f = new Fixture()) {
                switch (mode) {
                    case 0 -> patch(f.files.sources.get(5L), "MOMT", 4, 1);
                    case 1 -> patch(f.files.sources.get(5L), "MOMT", 24, 123);
                    case 2 -> patch(f.files.sources.get(5L), "MOMT", 44, 1);
                    case 3 ->
                            patch(f.files.sources.get(6L), "MONR", 8, Float.floatToRawIntBits(.9f));
                    case 4 -> patch(f.files.sources.get(6L), "MOBA", 12, 3);
                    case 5 -> patch(f.files.sources.get(6L), "MOBA", 20, 0x01000002);
                    case 6 -> f.files.sources.put(6L, group(chunk("MOBN", new byte[1])));
                    case 7 -> f.files.sources.put(5L, join(root(0), chunk("MODS", new byte[32])));
                    case 8 -> f.files.sources.get(10L)[9] = 8;
                    default -> throw new AssertionError();
                }
                var entered = new AtomicBoolean();
                assertThrows(
                        RuntimeException.class,
                        () ->
                                f.run(
                                        (borrow, scene, token) -> {
                                            entered.set(true);
                                            return (AutoCloseable) () -> {};
                                        }));
                assertFalse(entered.get(), "mode " + mode);
            }
        }
    }

    @Test
    void eachConsumedBindingIsRevalidatedAfterOutputAndClosedOnFailure() {
        for (long id : new long[] {1, 2, 4, 5, 6, 10}) {
            try (var f = new Fixture()) {
                var closed = new AtomicBoolean();
                assertThrows(
                        RuntimeException.class,
                        () ->
                                f.run(
                                        (borrow, scene, token) -> {
                                            f.files.changedId = id;
                                            f.files.changeAfterRead = true;
                                            return (AutoCloseable) () -> closed.set(true);
                                        }),
                        "id " + id);
                assertTrue(closed.get(), "id " + id);
            }
        }
    }

    @Test
    void publicSceneCannotMintSelectedRootGeometryAndMatchExpiresAfterCallback() throws Exception {
        try (var f = new Fixture()) {
            var original = f.scene.tiles().getFirst();
            float[] vertices = new float[original.vertices().remaining()];
            original.vertices().get(vertices);
            vertices[0] += 1;
            int[] indices = new int[original.indices().remaining()];
            original.indices().get(indices);
            var forged =
                    new TerrainScene(
                            List.of(
                                    TerrainScene.Tile.copyOf(
                                            7, 19, vertices, indices, original.chunks())));
            var entered = new AtomicBoolean();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            WorldBasicWmo.prepare(
                                    f.engine,
                                    f.scope,
                                    SELECTION,
                                    forged,
                                    (borrow, scene, token) -> {
                                        entered.set(true);
                                        return (AutoCloseable) () -> {};
                                    },
                                    Cancellation.NONE));
            assertFalse(entered.get());
            var match = new WorldBasicWmo.SceneMatch[1];
            f.run(
                            (borrow, scene, token) -> {
                                match[0] = scene;
                                return (AutoCloseable) () -> {};
                            })
                    .close();
            assertThrows(IllegalArgumentException.class, () -> match[0].scene());
        }
    }

    @Test
    void cancellationAfterOutputRetiresResultAndAdmissionCanBeClosed() {
        try (var f = new Fixture()) {
            var token = new Cancellation();
            var closed = new AtomicBoolean();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            WorldBasicWmo.prepare(
                                    f.engine,
                                    f.scope,
                                    SELECTION,
                                    f.scene,
                                    (borrow, scene, cancellation) -> {
                                        token.cancel();
                                        return (AutoCloseable) () -> closed.set(true);
                                    },
                                    token));
            assertTrue(closed.get());
        }
    }

    @Test
    void independentCardinalHeadingOraclesPreserveScaleAndWinding() throws Exception {
        int[] headings = {0, 90, 180, -90};
        double[][] offsets = {{-2, -4}, {4, -2}, {2, 4}, {-4, 2}};
        for (int i = 0; i < headings.length; i++) {
            try (var f = new Fixture()) {
                f.files.sources.put(
                        4L, join(version(18), chunk("MODF", object(5, 12, headings[i]))));
                double dx = offsets[i][0], dy = offsets[i][1];
                f.run(
                                (borrow, scene, token) -> {
                                    assertEquals(
                                            51200.0 / 3.0 - 300 + dx,
                                            borrow.transform().x(1, 2),
                                            1e-10);
                                    assertEquals(
                                            51200.0 / 3.0 - 100 + dy,
                                            borrow.transform().y(1, 2),
                                            1e-10);
                                    assertEquals(77, borrow.placement().uniqueId());
                                    assertEquals(0, borrow.indices().get(0));
                                    assertEquals(1, borrow.indices().get(1));
                                    assertEquals(2, borrow.indices().get(2));
                                    return (AutoCloseable) () -> {};
                                })
                        .close();
            }
        }
    }

    @Test
    void derivedSourceCapIsCheckedBeforeOpeningAndBorrowIsWorkerOnly() throws Exception {
        try (var f = new Fixture()) {
            f.files.sources.put(5L, new byte[1025]);
            var before = f.files.opens.get(5L);
            assertThrows(
                    RuntimeException.class,
                    () -> f.run((borrow, scene, token) -> (AutoCloseable) () -> {}));
            assertEquals(before, f.files.opens.get(5L));
        }
        try (var f = new Fixture()) {
            f.run(
                            (borrow, scene, token) -> {
                                var rejected = new AtomicBoolean();
                                var worker =
                                        new Thread(
                                                () -> {
                                                    try {
                                                        borrow.positions();
                                                    } catch (IllegalArgumentException expected) {
                                                        rejected.set(true);
                                                    }
                                                });
                                worker.start();
                                try {
                                    worker.join(5000);
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                                assertFalse(worker.isAlive());
                                assertTrue(rejected.get());
                                assertEquals(9, borrow.positions().remaining());
                                return (AutoCloseable) () -> {};
                            })
                    .close();
        }
    }

    private static final class UnknownSizes extends WorldTerrainTextureSourceTest.Files {
        final AtomicInteger derivedReads = new AtomicInteger();

        @Override
        FileTypes.FileStat stat(long id, Cancellation token) {
            var result = super.stat(id, token);
            if (id != 5 && id != 6 && id != 10) {
                return result;
            }
            var f = result.file();
            return new FileTypes.FileStat(
                    result.state(),
                    new FileTypes.BuildFile(
                            f.fileDataId(),
                            f.path(),
                            f.pathSource(),
                            null,
                            f.contentKey(),
                            f.format(),
                            f.formatSource(),
                            f.storage(),
                            f.encrypted()));
        }

        @Override
        FileReadStream open(long id, Cancellation token) {
            var actual = super.open(id, token);
            if (id != 5 && id != 6 && id != 10) {
                return actual;
            }
            return new FileReadStream() {
                public long length() {
                    return actual.length();
                }

                public long position() {
                    return actual.position();
                }

                public void seek(long position) {
                    actual.seek(position);
                }

                public List<FileTypes.FileHole> holes() {
                    return actual.holes();
                }

                public CompletableFuture<Integer> read(
                        ByteBuffer destination, Cancellation cancel) {
                    derivedReads.incrementAndGet();
                    return actual.read(destination, cancel);
                }

                public void close() {
                    actual.close();
                }
            };
        }
    }

    @Test
    void unknownBeforeReadSizesStayUnknownAndOversizeStreamDeclinesBeforeReading()
            throws Exception {
        var files = new UnknownSizes();
        try (var f = new Fixture(files)) {
            f.run(
                            (borrow, scene, token) -> {
                                for (var b : borrow.bindings()) {
                                    if (b.fileDataId() == 5
                                            || b.fileDataId() == 6
                                            || b.fileDataId() == 10) {
                                        assertEquals(null, b.declaredLength());
                                        assertEquals(
                                                files.sources.get(b.fileDataId()).length,
                                                b.length());
                                        assertEquals(
                                                b.fileDataId() == 5
                                                        ? 1024
                                                        : b.fileDataId() == 6 ? 32768 : 65536,
                                                b.limit());
                                    }
                                }
                                return (AutoCloseable) () -> {};
                            })
                    .close();
            assertTrue(files.derivedReads.get() > 0);
            files.derivedReads.set(0);
            files.sources.put(5L, new byte[1025]);
            int before = files.opens.get(5L).get();
            assertThrows(
                    RuntimeException.class,
                    () -> f.run((borrow, scene, token) -> (AutoCloseable) () -> {}));
            assertEquals(before + 1, files.opens.get(5L).get());
            assertEquals(
                    0,
                    files.derivedReads.get(),
                    "Unknown oversized stream length rejects before buffer read");
        }
    }
}
