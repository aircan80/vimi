package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class WorldHorizonTest {
    private static final long WDL = 900;
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", "Authored", 0, 1L);

    private static byte[] chunk(String tag, byte[] payload) {
        int code = 0;
        for (char c : tag.toCharArray()) {
            code = code << 8 | c;
        }
        return ByteBuffer.allocate(8 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(code)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /** One tile at 7,19 with corner (0, 0) = 321. */
    private static byte[] wdl() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                chunk(
                        "MVER",
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(18).array()));
        int maof = out.size();
        out.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        int at = out.size();
        var heights = ByteBuffer.allocate(545 * 2).order(ByteOrder.LITTLE_ENDIAN);
        heights.putShort(0, (short) 321);
        out.writeBytes(chunk("MARE", heights.array()));
        byte[] bytes = out.toByteArray();
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(maof + 8 + (7 * 64 + 19) * 4, at);
        return bytes;
    }

    /** Resolves the authored map's WDL path to {@link #WDL}; every other path is unknown. */
    private static BuildFiles byPath(
            WorldTerrainTextureSourceTest.Files files, AtomicInteger paths) {
        var delegate = files.files();
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) -> {
                            if ((method.getName().equals("stat")
                                            || method.getName().equals("statContent"))
                                    && ((FileTypes.FileRef) args[0]).path() != null) {
                                paths.incrementAndGet();
                                String path = ((FileTypes.FileRef) args[0]).path();
                                if (!path.equals("world/maps/authored/authored.wdl")) {
                                    return CompletableFuture.completedFuture(
                                            new FileTypes.FileStat(
                                                    FileTypes.FileLookupState.UNKNOWN_FILE, null));
                                }
                                args = new Object[] {FileTypes.FileRef.id(WDL), args[1]};
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    @Test
    void theRecordedWdlIsReadOnceAndCachedByContentKey() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.sources.put(WDL, wdl());
        var paths = new AtomicInteger();
        var horizon = new WorldHorizon(byPath(files, paths));
        try {
            for (int i = 0; i < 2; i++) {
                var result = horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS);
                assertEquals(WorldHorizon.State.READY, result.state());
                assertEquals(WDL, result.fileId());
                assertEquals(321, result.lowRes().tile(7, 19).corner(0, 0));
                assertEquals("1 tiles", result.reason());
            }
            assertEquals(1, files.opens(WDL));
            assertEquals(2, paths.get());
        } finally {
            horizon.closeAsync().get(10, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void missingUnusableMalformedOrProtectedWdlsHaveStatedStates() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var horizon = new WorldHorizon(byPath(files, new AtomicInteger()));
        try {
            // Not stored.
            assertEquals(
                    WorldHorizon.State.ABSENT,
                    horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS).state());
            // No usable directory.
            for (String directory : new String[] {null, "", "../x", "a/b"}) {
                var map = new WorldService.MapInfo(8, "x", directory, 0, 1L);
                var result = horizon.load(map, Cancellation.NONE).get(10, TimeUnit.SECONDS);
                assertEquals(WorldHorizon.State.ABSENT, result.state());
                assertNull(result.lowRes());
            }
            // Malformed.
            files.sources.put(WDL, new byte[64]);
            var malformed = horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS);
            assertEquals(WorldHorizon.State.UNAVAILABLE, malformed.state());
            assertTrue(malformed.reason().startsWith("WDL is malformed"));
        } finally {
            horizon.closeAsync().get(10, TimeUnit.SECONDS);
        }
        var guarded = new WorldTerrainTextureSourceTest.Files();
        guarded.sources.put(WDL, wdl());
        guarded.protectedId = WDL;
        var protectedHorizon = new WorldHorizon(byPath(guarded, new AtomicInteger()));
        try {
            var encrypted = protectedHorizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS);
            assertEquals(WorldHorizon.State.UNAVAILABLE, encrypted.state());
            assertTrue(encrypted.reason().startsWith("WDL is encrypted"), encrypted.reason());
            assertEquals(WDL, encrypted.fileId());
        } finally {
            protectedHorizon.closeAsync().get(10, TimeUnit.SECONDS);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldHorizon.Result(
                                WorldHorizon.State.READY, 7, WDL, null, "missing heights"));
    }

    @Test
    void pathsAreLowerCasedAndClosedSourcesRefuse() throws Exception {
        assertEquals("world/maps/azeroth/azeroth.wdl", WorldHorizon.path("Azeroth"));
        assertEquals("world/maps/expansion01/expansion01.wdl", WorldHorizon.path("Expansion01"));
        // The recorded directory is used as is, like ADT paths; only blank ones are refused.
        assertEquals("world/maps/a b'c/a b'c.wdl", WorldHorizon.path("A B'c"));
        assertNull(WorldHorizon.path(" "));
        var horizon = new WorldHorizon(null);
        assertEquals(
                WorldHorizon.State.ABSENT,
                horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS).state());
        horizon.closeAsync().get(10, TimeUnit.SECONDS);
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS));
    }

    @Test
    void aFullQueueAnswersUnavailableAndCanceledQueuedRequestsFreeTheirSlots() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var files = new WorldTerrainTextureSourceTest.Files();
        files.sources.put(WDL, wdl());
        var resolving = byPath(files, new AtomicInteger());
        // The first lookup blocks the single worker until released.
        var blocking =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("statContent")
                                            && started.getCount() == 1) {
                                        started.countDown();
                                        release.await(10, TimeUnit.SECONDS);
                                    }
                                    try {
                                        return method.invoke(resolving, args);
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
        var horizon = new WorldHorizon(blocking);
        try {
            var running = horizon.load(MAP, Cancellation.NONE);
            assertTrue(started.await(10, TimeUnit.SECONDS));
            var waitingToken = new Cancellation();
            var waiting = horizon.load(MAP, waitingToken);
            var second = horizon.load(MAP, Cancellation.NONE);
            var full = horizon.load(MAP, Cancellation.NONE).get(10, TimeUnit.SECONDS);
            assertEquals(WorldHorizon.State.UNAVAILABLE, full.state());
            assertEquals("Horizon request queue is full", full.reason());
            // Canceling a queued request frees its slot at once.
            waitingToken.cancel();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(10, TimeUnit.SECONDS));
            var accepted = horizon.load(MAP, Cancellation.NONE);
            release.countDown();
            for (var future : java.util.List.of(running, second, accepted)) {
                assertEquals(WorldHorizon.State.READY, future.get(10, TimeUnit.SECONDS).state());
            }
        } finally {
            release.countDown();
            horizon.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }
}
