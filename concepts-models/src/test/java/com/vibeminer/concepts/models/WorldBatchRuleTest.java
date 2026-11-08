package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.rendering.models.MeshAppearance;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The World binding's batch rule (#361), which follows the World objects' rule (#360). */
class WorldBatchRuleTest {
    private static boolean world(NativeMaterialFixture fixture) {
        var parsed = fixture.parse();
        return DisplayModelService.worldBatch(parsed.model(), parsed.rest().batches().getFirst());
    }

    @Test
    void simpleOpaqueAndAlphaKeyedBatchesDrawInBothProfiles() throws Exception {
        var opaque = new NativeMaterialFixture(274, 16, 0, 1, 0, 0);
        assertNotNull(opaque.parse().profile());
        assertTrue(world(opaque));
        assertTrue(world(new NativeMaterialFixture(274, 16, 0, 1, 0, 1)));
    }

    @Test
    void environmentCombinersDrawTextureOneWhereThePreviewRefusesThem() throws Exception {
        // Combiners_Opaque_Mod2xNA_Alpha with Diffuse_T1_Env: texture 1 is sampled with UV0.
        var effect = new NativeMaterialFixture(274, 0x90, 0x8000, 2, 0, 0);
        assertNull(effect.parse().profile());
        assertTrue(world(effect));
        // Combiners_Opaque_AddAlpha, unlit.
        var add = new NativeMaterialFixture(274, 0x90, 0x8001, 2, 1, 0);
        assertNull(add.parse().profile());
        assertTrue(world(add));
    }

    @Test
    void renderFlagsAndTextureTransformsAreNotConsulted() throws Exception {
        var unlit = new NativeMaterialFixture(274, 16, 0, 1, 1, 0);
        assertNull(unlit.parse().profile());
        assertTrue(world(unlit));
        var animated = new NativeMaterialFixture(274, 16, 0, 1, 0, 0);
        animated.body.putShort(animated.transforms, (short) 0);
        assertNull(animated.parse().profile());
        assertTrue(world(animated));
    }

    @Test
    void blendedAdditiveAndNonUv0BatchesStayOut() throws Exception {
        assertFalse(
                world(new NativeMaterialFixture(274, 16, 0, 1, 0, 2)), "blended: no creature pass");
        assertFalse(world(new NativeMaterialFixture(274, 16, 16, 1, 17, 4)), "additive glow");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x80, 1, 0, 0)), "Diffuse_Env");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x4000, 1, 0, 0)), "Diffuse_T2");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x7fff, 1, 0, 0)), "unnamed bits");
        assertFalse(
                world(new NativeMaterialFixture(274, 16, 0x8013, 1, 0, 0)), "Diffuse_EdgeFade_T1");
        var layered = new NativeMaterialFixture(274, 16, 0, 1, 0, 0);
        layered.skin.putShort(152, (short) 1);
        assertEquals(1, layered.parse().rest().batches().getFirst().materialLayer());
        assertFalse(world(layered), "material layer 1");
    }

    @Test
    void worldBindingDrawsWhatThePreviewRefusesUnderItsOwnSavedKey() throws Exception {
        var f = new NativeMaterialFixture(274, 0x90, 0x8000, 2, 0, 0);
        var files =
                new AssetFilesFixture()
                        .add(100, "1".repeat(32), f.modelBytes())
                        .add(
                                9000,
                                "2".repeat(32),
                                Arrays.copyOf(f.skin.array(), f.skin.position()));
        byte[] blp;
        try (var in = getClass().getResourceAsStream("/golden/source.blp")) {
            blp = in.readAllBytes();
        }
        files.add(9001, "3".repeat(32), blp).add(9002, "4".repeat(32), blp);
        var store = new MemoryGeneratedStore();
        try (var tables =
                new MemoryTables()
                        .add(
                                files.build(),
                                "enUS",
                                List.of(
                                        NativeMaterialFailureTest.table(
                                                10,
                                                "CreatureDisplayInfo",
                                                "ModelID",
                                                "CreatureModelData",
                                                1),
                                        NativeMaterialFailureTest.table(
                                                11,
                                                "CreatureModelData",
                                                "FileDataID",
                                                "FileData",
                                                100)))
                        .forBuild(files.build(), "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new DisplayModelService(tables, files, store);
            try {
                var preview = service.preview(1, Cancellation.NONE).join();
                assertNotNull(preview.appearance(), preview.message());
                assertTrue(
                        preview.appearance().surfaces().isEmpty(),
                        "the preview keeps its reviewed profiles");
                assertFalse(preview.issues().contains(DisplayModelService.WORLD_NOTE));

                var world = service.worldPreview(1, Cancellation.NONE).join();
                assertFalse(world.restored());
                var surfaces = world.appearance().surfaces();
                assertEquals(1, surfaces.size());
                var material = surfaces.getFirst().material();
                assertEquals(MeshAppearance.Combiner.DIFFUSE, material.combiner());
                assertEquals(MeshAppearance.Blend.OPAQUE, material.blend());
                assertEquals(1, material.stages().size(), "texture 1 only");
                assertTrue(world.issues().contains(DisplayModelService.WORLD_NOTE));

                var again = service.worldPreview(1, Cancellation.NONE).join();
                assertTrue(again.restored(), "saved under the World key");
                assertEquals(1, again.appearance().surfaces().size());
                assertTrue(again.issues().contains(DisplayModelService.WORLD_NOTE));
                var previewAgain = service.preview(1, Cancellation.NONE).join();
                assertTrue(previewAgain.appearance().surfaces().isEmpty(), "keys do not collide");
            } finally {
                service.closeAsync().join();
            }
        } finally {
            files.close();
        }
    }
}
