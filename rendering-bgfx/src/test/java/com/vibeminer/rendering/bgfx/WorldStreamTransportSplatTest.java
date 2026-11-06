package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lifecycle of the parent-owned splat layer array through real transport reservations (issue #174
 * step 2b-1): a packet binds at most {@link WorldStreamTileFile#MAX_SPLAT_LAYERS} new layers, a
 * dropped upload releases its layer, a failed or canceled tile forgets every layer it never
 * uploaded, and a staged tile keeps them for the next tile to reuse.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportSplatTest {
    @TempDir Path directory;

    private static List<WorldStreamDiffuseArray.Key> keys(int count, int from) {
        var keys = new ArrayList<WorldStreamDiffuseArray.Key>();
        for (int i = 0; i < count; i++) {
            keys.add(new WorldStreamDiffuseArray.Key(from + i, String.format("%032x", from + i)));
        }
        return keys;
    }

    /** A session whose authored child grants one credit, then idles until closed. */
    private WorldStreamTransport.Session session() throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        var ended = new CountDownLatch(1);
        child.onExit().thenRun(ended::countDown);
        var own = new AtomicReference<Path>();
        var prepared =
                WorldStreamTransport.prepare(
                        directory,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        var session =
                prepared.launch(
                        arguments -> {
                            own.set(Path.of(arguments.get(1)));
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(own.get().resolve("session.wss")),
                                            arguments.get(3));
                            String prefix =
                                    "WS2 "
                                            + HexFormat.of().toHexDigits(descriptor.nonce().high())
                                            + HexFormat.of().toHexDigits(descriptor.nonce().low())
                                            + " ";
                            byte[] bytes =
                                    (prefix
                                                    + "SESSION TERRAIN_BASIC_V1\n"
                                                    + prefix
                                                    + "CREDIT 1 0 1\n")
                                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                            child.input =
                                    new java.io.InputStream() {
                                        int at;

                                        @Override
                                        public int read() throws java.io.IOException {
                                            if (at < bytes.length) {
                                                return bytes[at++] & 255;
                                            }
                                            try {
                                                ended.await();
                                            } catch (InterruptedException error) {
                                                throw new java.io.IOException(error);
                                            }
                                            return -1;
                                        }
                                    };
                            return child;
                        });
        session.initialized().get(2, TimeUnit.SECONDS);
        return session;
    }

    /** The first reservation, once the authored credit has arrived. */
    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var ticket = session.tryReserveTile(7, 19);
            if (ticket.isPresent()) {
                return ticket.get();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No initial credit");
    }

    private static void close(WorldStreamTransport.Session session) {
        session.closeAsync();
        try {
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            /* An authored idle child ends by teardown. */
        }
    }

    @Test
    void aFailedTileForgetsEveryLayerItNeverUploaded() throws Exception {
        var session = session();
        try {
            var reservation = reserve(session);
            var bindings = new AtomicReference<WorldStreamTransport.SplatBinding>();
            var resident = new AtomicReference<Integer>();
            var produced =
                    reservation.produce(
                            (destination, stopping) -> {
                                // Seventeen new keys: sixteen layers this packet uploads, then -1.
                                var binding = destination.bindSplat(keys(17, 100));
                                bindings.set(binding);
                                // One upload this packet will not carry releases its layer.
                                destination.dropSplatUpload(binding.uploads().getFirst());
                                resident.set(session.splatResident());
                                throw new java.io.IOException("Authored producer failure");
                            });
            assertThrows(ExecutionException.class, () -> produced.get(5, TimeUnit.SECONDS));
            var binding = bindings.get();
            assertEquals(WorldStreamTileFile.MAX_SPLAT_LAYERS, binding.uploads().size());
            assertEquals(-1, binding.layers()[16], "Beyond the per-packet cap: keeps the atlas");
            for (int i = 0; i < 16; i++) {
                assertTrue(
                        binding.layers()[i] >= 0
                                && binding.layers()[i] < WorldStreamDiffuseArray.CAPACITY);
            }
            assertEquals(15, resident.get(), "The dropped upload was released before writing");
            assertEquals(0, session.splatResident(), "A failed tile forgets its pending layers");
        } finally {
            close(session);
        }
    }

    /**
     * Past 255 distinct diffuses, every binding still assembles into a valid packet trailer, as
     * {@link WorldStreamTileWriter.PacketFile#splat} builds it: no layer id is ever {@link
     * WorldStreamTileFile#SPLAT_NONE}, so no tile fails on its trailer (review of #435).
     */
    @Test
    void layerIdsNeverReachTheNoneMarkerPast255Diffuses() throws Exception {
        var session = session();
        try {
            var reservation = reserve(session);
            var trailers = new java.util.concurrent.atomic.AtomicInteger();
            var produced =
                    reservation.produce(
                            (destination, stopping) -> {
                                for (int round = 0; round < 20; round++) {
                                    // Sixteen new diffuses per packet: 320 in all.
                                    var binding =
                                            destination.bindSplat(keys(16, 1000 + round * 16));
                                    byte[] table = new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES];
                                    java.util.Arrays.fill(
                                            table, (byte) WorldStreamTileFile.SPLAT_NONE);
                                    for (int i = 0; i < binding.layers().length; i++) {
                                        int layer = binding.layers()[i];
                                        assertTrue(
                                                layer >= -1
                                                        && layer < WorldStreamTileFile.SPLAT_NONE,
                                                "round " + round + " layer " + layer);
                                        if (layer >= 0) {
                                            table[i] = (byte) layer;
                                        }
                                    }
                                    int[] uploads =
                                            binding.uploads().stream()
                                                    .mapToInt(Integer::intValue)
                                                    .toArray();
                                    new WorldStreamTileFile.Splat(
                                            table,
                                            new byte[WorldStreamTileFile.SPLAT_ALPHA_BYTES],
                                            uploads,
                                            new byte[uploads.length]
                                                    [WorldStreamTileFile.SPLAT_LAYER_BYTES]);
                                    trailers.incrementAndGet();
                                }
                                assertEquals(
                                        WorldStreamDiffuseArray.CAPACITY, session.splatResident());
                                throw new java.io.IOException("Authored end");
                            });
            assertThrows(ExecutionException.class, () -> produced.get(10, TimeUnit.SECONDS));
            assertEquals(20, trailers.get(), "every binding made a valid trailer");
        } finally {
            close(session);
        }
    }

    @Test
    void aStagedTileKeepsItsLayersForTheNextTile() throws Exception {
        var session = session();
        try {
            var reservation = reserve(session);
            var binding = new AtomicReference<WorldStreamTransport.SplatBinding>();
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var produced =
                    reservation.produce(
                            (destination, stopping) -> {
                                binding.set(destination.bindSplat(keys(3, 200)));
                                held.countDown();
                                release.await();
                                throw new java.io.IOException("Authored end after staging");
                            });
            assertTrue(held.await(5, TimeUnit.SECONDS));
            assertEquals(3, binding.get().uploads().size());
            // As the transport does once the child stages the tile: the result completes normally.
            var result = WorldStreamTransport.TileReservation.class.getDeclaredField("result");
            result.setAccessible(true);
            @SuppressWarnings("unchecked")
            var future =
                    (java.util.concurrent.CompletableFuture<WorldStreamTransport.Resident>)
                            result.get(reservation);
            future.complete(null);
            assertEquals(3, session.splatResident(), "Staged layers stay resident");
            release.countDown();
            try {
                produced.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                /* The authored producer's own end does not undo the staged result. */
            }
            assertEquals(3, session.splatResident(), "A later producer end forgets nothing");
            assertEquals(3, session.splatReferenced());
            // The slot's next tile is staged without splat layers (untextured, or no splat
            // inputs): the old layers stay cached but are no longer pinned by the slot.
            var bound = WorldStreamTransport.TileReservation.class.getDeclaredField("splatBound");
            bound.setAccessible(true);
            bound.setBoolean(reservation, false);
            var keep =
                    WorldStreamTransport.Session.class.getDeclaredMethod(
                            "keepSplat", WorldStreamTransport.TileReservation.class);
            keep.setAccessible(true);
            keep.invoke(session, reservation);
            assertEquals(0, session.splatReferenced(), "An unsplatted successor unpins the slot");
            assertEquals(3, session.splatResident(), "Unpinned layers stay cached for reuse");
        } finally {
            close(session);
        }
    }
}
