package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorldWmoPresentationTest {
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
    void onlyActualFactoryChainMakesExpiringNeutralBorrowWithCanonicalRoles() throws Exception {
        try (var f = new Fixture()) {
            var saved = new BasicWmoData.View[1];
            var closed = new AtomicBoolean();
            AutoCloseable result =
                    WorldWmoPresentation.prepare(
                            f.engine,
                            f.scope,
                            SELECTION,
                            f.scene,
                            (view, scene, token) -> {
                                saved[0] = view;
                                assertEquals(f.scene, scene);
                                assertEquals(77, view.metadata().uniqueId());
                                assertEquals(0, view.metadata().tileOrdinal());
                                assertEquals(6, view.sources().size());
                                assertEquals(0, view.sources().get(0).role());
                                assertEquals(5, view.sources().get(5).role());
                                assertEquals(10, view.image(0).id());
                                assertEquals(10, view.materialWord(0, 3));
                                assertEquals(1, view.batches().size());
                                assertTrue(view.positions().isReadOnly());
                                assertTrue(view.image(0).rgba().isReadOnly());
                                return (AutoCloseable) () -> closed.set(true);
                            },
                            Cancellation.NONE);
            assertFalse(closed.get());
            assertThrows(IllegalArgumentException.class, () -> saved[0].metadata());
            assertThrows(IllegalArgumentException.class, () -> saved[0].sources());
            assertThrows(IllegalArgumentException.class, () -> saved[0].image(0));
            result.close();
            assertTrue(closed.get());
        }
    }

    @Test
    void finalSourceChangeClosesUnadvertisedAdapterResultAndCallbackCannotEscape() {
        try (var f = new Fixture()) {
            var saved = new BasicWmoData.View[1];
            var closed = new AtomicBoolean();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            WorldWmoPresentation.prepare(
                                    f.engine,
                                    f.scope,
                                    SELECTION,
                                    f.scene,
                                    (view, scene, token) -> {
                                        saved[0] = view;
                                        f.files.changedId = 5;
                                        f.files.changeAfterRead = true;
                                        return (AutoCloseable) () -> closed.set(true);
                                    },
                                    Cancellation.NONE));
            assertTrue(closed.get());
            assertThrows(IllegalArgumentException.class, () -> saved[0].positions());
        }
    }
}
