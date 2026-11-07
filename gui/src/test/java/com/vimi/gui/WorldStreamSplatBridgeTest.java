package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.rendering.bgfx.AuthoredPacketDestination;
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.TerrainLayerBlocks;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The source bridge's splatting inputs (issue #174 step 2b-1), with {@code -Dvimi.world.splat=true}:
 * every distinct layer diffuse of an authored textured tile maps to its material's content key,
 * and the writer's decoder yields that diffuse as one 256 x 256 BC1 layer chain whose colour is
 * the diffuse's own. A diffuse outside the tile's material yields nothing. Authored data only.
 */
public final class WorldStreamSplatBridgeTest {
    private WorldStreamSplatBridgeTest() {}

    public static void main(String[] args) throws Exception {
        check(Boolean.getBoolean("vimi.world.splat"), "Run with -Dvimi.world.splat=true");
        try {
            run(com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD);
            run(com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH);
        } finally {
            com.vibeminer.concepts.world.WorldTerrainStreaming.splatMasks(
                    com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD);
        }
    }

    /** One authored tile composed at {@code detail}'s mask side, through the bridge. */
    private static void run(com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail detail)
            throws Exception {
        com.vibeminer.concepts.world.WorldTerrainStreaming.splatMasks(detail);
        int side =
                detail == com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH
                        ? com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK
                        : com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;
        var fixture = new WorldStreamSourceBridgeTest.Fixture();
        var path = Files.createTempDirectory("bridge-splat-").resolve("tile-1.wst");
        var layers = new AtomicInteger();
        try {
            var scope = fixture.scope();
            try (var producer =
                    new WorldStreamSourceBridge.Producer(
                            fixture.source, scope, WorldStreamSourceBridgeTest.TILE, () -> true)) {
                var packet =
                        producer.prepare(
                                AuthoredPacketDestination.create(path, scope.scopeSha256()),
                                () -> false);
                try (packet;
                        var resident = producer.takePrepared()) {
                    resident.withPayload(
                            borrow -> {
                                var splat = borrow.splat().orElseThrow();
                                var material = borrow.material();
                                check(material != null, "Textured authored tile");
                                check(splat.maskSide() == side, "Mask side follows the tier");
                                var input =
                                        WorldStreamSourceBridge.splatInput(
                                                borrow, material, splat, () -> false);
                                check(
                                        input.alpha().remaining() == side * side * 4,
                                        "The writer receives the tier's mask map");
                                check(
                                        input.fileIds().length == splat.diffuseCount()
                                                && input.fileIds().length > 0,
                                        "One input key per distinct diffuse");
                                for (int i = 0; i < input.fileIds().length; i++) {
                                    String key = null;
                                    for (int d = 0; d < material.diffuseCount(); d++) {
                                        var source = material.diffuse(d).source();
                                        if (source.fileDataId() == input.fileIds()[i]) {
                                            key = source.contentKey();
                                        }
                                    }
                                    check(
                                            input.contentKeys()[i].equals(key),
                                            "Key is the material's content key");
                                    var diffuse =
                                            input.decode().apply(input.fileIds()[i]).orElseThrow();
                                    var chain = diffuse.chain();
                                    check(
                                            chain.remaining() == TerrainLayerBlocks.CHAIN_BYTES,
                                            "One BC1 layer chain");
                                    // The layer's 1 x 1 level is the diffuse's mean colour; it is
                                    // never black for an authored coloured diffuse.
                                    var texel =
                                            ObjectTextureFormats.decodeLevel(
                                                    ObjectTextureFormats.BC1,
                                                    1,
                                                    1,
                                                    chain,
                                                    chain.position()
                                                            + TerrainLayerBlocks.levelOffset(8));
                                    check(
                                            (texel[0] | texel[1] | texel[2]) != 0,
                                            "Layer colour carried");
                                    layers.incrementAndGet();
                                }
                                var counts = borrow.splatLayerCounts();
                                check(
                                        counts.fileBlocks() + counts.encoded() == layers.get(),
                                        "Every prepared layer is counted by how it was prepared");
                                // A stopped producer reads no further diffuse.
                                var stopped =
                                        WorldStreamSourceBridge.splatInput(
                                                borrow, material, splat, () -> true);
                                try {
                                    stopped.decode().apply(input.fileIds()[0]);
                                    // A cached detail needs no read and may still be returned.
                                } catch (RuntimeException canceled) {
                                    check(
                                            canceled instanceof java.util.concurrent.CancellationException
                                                    || canceled.getCause()
                                                            instanceof java.util.concurrent.CancellationException,
                                            "A stopped decode ends as a cancellation: " + canceled);
                                }
                                check(
                                        input.decode().apply(0xfffffL).isEmpty(),
                                        "A diffuse outside the material yields no layer");
                                check(
                                        borrow.diffuseDetail(input.fileIds()[0], Cancellation.NONE)
                                                .orElseThrow()
                                                .chain()
                                                .isReadOnly(),
                                        "Detail chains are read-only views");
                            });
                }
            }
            System.out.println(
                    "World splat bridge ("
                            + detail
                            + ", mask "
                            + side
                            + "): "
                            + layers.get()
                            + " layer chains mapped");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void check(boolean valid, String detail) {
        if (!valid) {
            throw new AssertionError(detail);
        }
    }
}
