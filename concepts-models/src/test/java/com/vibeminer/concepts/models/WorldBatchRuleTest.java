package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.rendering.models.MeshAppearance;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
                world(new NativeMaterialFixture(274, 16, 0, 1, 0, 2)),
                "blended: the blended rule's, not this one");
        assertFalse(
                world(new NativeMaterialFixture(274, 16, 16, 1, 17, 4)),
                "additive glow: the additive rule's, not this one");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x80, 1, 0, 0)), "Diffuse_Env");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x4000, 1, 0, 0)), "Diffuse_T2");
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x7fff, 1, 0, 0)), "Diffuse_Env bit");
        // Combiners_Opaque with Diffuse_T1 plus a bit the naming does not read.
        assertFalse(world(new NativeMaterialFixture(274, 16, 0x0100, 1, 0, 0)), "unnamed bit");
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

    /** The World binding of display 1 over the fixture, with the given texture files left out. */
    static DisplayModelTypes.Preview worldBinding(NativeMaterialFixture f, long... missing)
            throws Exception {
        return worldBindings(f, 1, missing).getFirst();
    }

    /**
     * {@code calls} World bindings of display 1 over the fixture from one service and store, so a
     * later call restores what an earlier one saved.
     */
    static List<DisplayModelTypes.Preview> worldBindings(
            NativeMaterialFixture f, int calls, long... missing) throws Exception {
        return worldBindings(
                f,
                NativeMaterialFailureTest.table(
                        10, "CreatureDisplayInfo", "ModelID", "CreatureModelData", 1),
                calls,
                missing);
    }

    /**
     * As {@link #worldBindings(NativeMaterialFixture, int, long...)} over the given display table.
     */
    static List<DisplayModelTypes.Preview> worldBindings(
            NativeMaterialFixture f, MemoryTables.FakeTable displays, int calls, long... missing)
            throws Exception {
        var files =
                new AssetFilesFixture()
                        .add(100, "1".repeat(32), f.modelBytes())
                        .add(
                                9000,
                                "2".repeat(32),
                                Arrays.copyOf(f.skin.array(), f.skin.position()));
        byte[] blp;
        try (var in = WorldBatchRuleTest.class.getResourceAsStream("/golden/source.blp")) {
            blp = in.readAllBytes();
        }
        for (long fid : new long[] {9001, 9002}) {
            if (Arrays.stream(missing).noneMatch(m -> m == fid)) {
                files.add(fid, Long.toString(fid).repeat(8), blp);
            }
        }
        try (var tables =
                new MemoryTables()
                        .add(
                                files.build(),
                                "enUS",
                                List.of(
                                        displays,
                                        NativeMaterialFailureTest.table(
                                                11,
                                                "CreatureModelData",
                                                "FileDataID",
                                                "FileData",
                                                100)))
                        .forBuild(files.build(), "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new DisplayModelService(tables, files, new MemoryGeneratedStore());
            try {
                var out = new java.util.ArrayList<DisplayModelTypes.Preview>();
                for (int i = 0; i < calls; i++) {
                    out.add(service.worldPreview(1, Cancellation.NONE).join());
                }
                return out;
            } finally {
                service.closeAsync().join();
            }
        } finally {
            files.close();
        }
    }

    /**
     * A CreatureDisplayInfo table whose display 1 names model 1 and the four texture variations
     * {@code variations}, in the reviewed array shape.
     */
    static MemoryTables.FakeTable displaysWithVariations(long... variations) {
        var locale = WowLocale.parse("enUS");
        var model =
                new TableSchema.SchemaColumn(
                        "ModelID",
                        TableSchema.ColumnKind.INT,
                        32,
                        true,
                        1,
                        new TableSchema.ForeignKey("CreatureModelData", "ID"),
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        locale,
                        null,
                        null);
        var textures =
                new TableSchema.SchemaColumn(
                        "TextureVariationFileDataID",
                        TableSchema.ColumnKind.INT,
                        32,
                        true,
                        4,
                        new TableSchema.ForeignKey("FileData", "ID"),
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        locale,
                        null,
                        null);
        var columns = new java.util.ArrayList<Db2Types.Column>();
        var values = new java.util.ArrayList<String>();
        columns.add(new Db2Types.Column("ModelID", "integer", false, null, null));
        values.add("1");
        for (int i = 0; i < 4; i++) {
            columns.add(
                    new Db2Types.Column(
                            "TextureVariationFileDataID[" + i + "]", "integer", false, null, null));
            values.add(Long.toString(variations[i]));
        }
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        10,
                        "CreatureDisplayInfo",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(1, 1, 0, 0, "fixture", "0FC0F0D5"),
                        "c".repeat(32)),
                columns,
                List.of(new Db2Types.Row(1, false, null, values, List.of())),
                new TableSchema(
                        List.of(model, textures),
                        List.of("0FC0F0D5"),
                        TableSchema.SchemaMatch.BUILD,
                        locale,
                        null),
                Map.of(),
                true);
    }

    @Test
    void anObjectSkinSlotDrawsTheDisplaysFirstVariationWhenTheModelHasNoOtherReplaceableSlot()
            throws Exception {
        // Texture 0 (batch 0's only texture) is an object skin slot (type 2); its TXID 9001 is
        // left out of the files, so only the display's variation can bind it.
        var f = new NativeMaterialFixture(274, 16, 0, 1, 0, 0);
        f.body.putInt(f.textures, 2);
        assertEquals(2, f.parse().model().textures().getFirst().type());

        var world =
                worldBindings(f, displaysWithVariations(9002, 9002, 9002, 9002), 1, 9001)
                        .getFirst();
        assertEquals(1, world.appearance().surfaces().size(), world.issues().toString());
        assertEquals(1, world.appearance().textures().size(), "texture 9002 only");

        // No first variation: the slot stays unbound and the section is left out.
        assertUndrawn(worldBindings(f, displaysWithVariations(0, 9002, 0, 0), 1, 9001));

        // Another replaceable slot on the model (a monster skin, which owns variation 0, or a
        // character slot, whose type 2 is a cape): type 2 stays unbound.
        for (int other : new int[] {11, 1}) {
            f.body.putInt(f.textures + 16, other);
            assertUndrawn(
                    worldBindings(f, displaysWithVariations(9002, 9002, 9002, 9002), 1, 9001));
        }

        // The character skin (type 1) is never bound from a variation.
        f.body.putInt(f.textures + 16, 0);
        f.body.putInt(f.textures, 1);
        assertUndrawn(worldBindings(f, displaysWithVariations(9002, 9002, 9002, 9002), 1, 9001));
    }

    private static void assertUndrawn(List<DisplayModelTypes.Preview> bindings) {
        var binding = bindings.getFirst();
        assertTrue(
                binding.appearance() == null || binding.appearance().surfaces().isEmpty(),
                binding.issues().toString());
    }

    @Test
    void anAlphaKeyedBatchBindsAnAlphaKeyedSurface() throws Exception {
        var world = worldBinding(new NativeMaterialFixture(274, 0x90, 0x8000, 2, 0, 1));
        var surfaces = world.appearance().surfaces();
        assertEquals(1, surfaces.size());
        assertEquals(MeshAppearance.Blend.ALPHA_KEY, surfaces.getFirst().material().blend());
        var opaque = worldBinding(new NativeMaterialFixture(274, 0x90, 0x8000, 2, 0, 0));
        assertEquals(
                MeshAppearance.Blend.OPAQUE,
                opaque.appearance().surfaces().getFirst().material().blend());
    }

    @Test
    void aSectionWhoseFirstTextureIsMissingDrawsItsNextBatch() throws Exception {
        var f = new NativeMaterialFixture(274, 16, 0, 1, 0, 0);
        // A second batch on section 0, naming texture combo 1 (texture 9002).
        byte[] batch = Arrays.copyOfRange(f.skin.array(), 140, 164);
        f.skin.position(164);
        f.skin.put(batch);
        f.skin.putShort(164 + 16, (short) 1);
        f.skin.putInt(36, 2);
        var parsed = f.parse();
        assertEquals(2, parsed.rest().batches().size());
        assertEquals(0, parsed.rest().batches().get(1).sectionIndex());

        var world = worldBinding(f, 9001);
        assertEquals(1, world.appearance().surfaces().size(), world.issues().toString());
        assertEquals(1, world.appearance().textures().size(), "only texture 9002 is decoded");
        assertTrue(
                world.issues().stream().anyMatch(s -> s.contains("9001")),
                world.issues().toString());

        var none = worldBinding(f, 9001, 9002);
        assertTrue(none.appearance().surfaces().isEmpty(), "no texture, no surface");
    }

    /** Points batch 0's texture weight at nothing (65535). */
    private static void noWeight(NativeMaterialFixture f) {
        int lookup = f.body.position();
        f.declaration(144, 1, lookup);
        f.body.putShort((short) -1).putShort((short) 0);
    }

    /**
     * Points batch 0's texture weight at track 0, whose one sequence slice holds {@code keys} keys
     * of weight 0 (no keys: the track never fades).
     */
    private static void weightTrack(NativeMaterialFixture f, int keys) {
        int track = f.body.position();
        f.declaration(88, 1, track);
        f.body.putShort((short) 0).putShort((short) -1).putInt(0).putInt(0);
        f.body.putInt(1).putInt(track + 20);
        f.body.putInt(keys).putInt(track + 28);
        for (int i = 0; i < keys; i++) {
            f.body.putShort((short) 0);
        }
        f.body.position((f.body.position() + 3) & ~3);
        int lookup = f.body.position();
        f.declaration(144, 1, lookup);
        f.body.putShort((short) 0).putShort((short) 0);
    }

    private static boolean additive(NativeMaterialFixture f, boolean withFacts) {
        var parsed = f.parse();
        return DisplayModelService.worldAdditiveBatch(
                parsed.model(),
                parsed.rest().batches().getFirst(),
                withFacts ? parsed.appearance() : null);
    }

    /** An additive glow batch: Combiners_Mod / Diffuse_T1, unlit, blend 4. */
    private static NativeMaterialFixture glow() throws Exception {
        return new NativeMaterialFixture(274, 16, 16, 1, 0x11, 4);
    }

    @Test
    void additiveBatchesDrawOnlyWithAWeightThatStaysFull() throws Exception {
        var none = glow();
        noWeight(none);
        assertEquals(65535, none.parse().model().textureWeights().getFirst());
        assertFalse(world(none), "not an opaque or alpha-keyed batch");
        assertTrue(additive(none, true), "no weight track");
        assertTrue(additive(none, false), "no weight track needs no facts");

        var steady = glow();
        weightTrack(steady, 0);
        assertEquals(0, steady.parse().model().textureWeights().getFirst());
        assertTrue(additive(steady, true), "a weight track without keys never fades");
        assertFalse(additive(steady, false), "a weight track without facts is not drawn");

        var fading = glow();
        weightTrack(fading, 1);
        assertFalse(additive(fading, true), "a weight key below full may hide the part");

        var opaque = new NativeMaterialFixture(274, 16, 16, 1, 0x11, 0);
        noWeight(opaque);
        assertFalse(additive(opaque, true), "blend 0 is the opaque rule's");
        for (int blend : new int[] {2, 3, 5, 6, 7}) {
            var other = new NativeMaterialFixture(274, 16, 16, 1, 0x11, blend);
            noWeight(other);
            assertFalse(additive(other, true), "blend " + blend);
        }
        var env = new NativeMaterialFixture(274, 16, 0x80, 1, 0x11, 4);
        noWeight(env);
        assertFalse(additive(env, true), "Diffuse_Env");
        var layered = glow();
        noWeight(layered);
        layered.skin.putShort(152, (short) 1);
        assertFalse(additive(layered, true), "material layer 1");
    }

    @Test
    void anAdditiveOnlySectionBindsAnUnlitAdditiveSurfaceUnderTheWorldKey() throws Exception {
        var f = glow();
        noWeight(f);
        var world = worldBinding(f);
        var surfaces = world.appearance().surfaces();
        assertEquals(1, surfaces.size(), world.issues().toString());
        var material = surfaces.getFirst().material();
        assertEquals(MeshAppearance.Combiner.DIFFUSE, material.combiner());
        assertEquals(MeshAppearance.Blend.ADDITIVE, material.blend());
        assertEquals(MeshAppearance.Lighting.UNLIT, material.lighting());
        assertEquals(1, material.stages().size(), "texture 1 only");
        assertTrue(world.appearance().billboards().isEmpty(), "no rigid cards");
        assertTrue(world.issues().contains(DisplayModelService.WORLD_NOTE));

        var fading = glow();
        weightTrack(fading, 1);
        assertTrue(worldBinding(fading).appearance().surfaces().isEmpty(), "fading: no surface");
    }

    @Test
    void anOpaqueBatchClaimsItsSectionAheadOfAnEarlierAdditiveOne() throws Exception {
        var f = glow();
        noWeight(f);
        // Material 1 opaque; a second batch on section 0 with material 1 and texture combo 1.
        int materials = f.body.position();
        f.declaration(112, 2, materials);
        f.body.putShort((short) 0x11).putShort((short) 4).putShort((short) 0).putShort((short) 0);
        byte[] batch = Arrays.copyOfRange(f.skin.array(), 140, 164);
        f.skin.position(164);
        f.skin.put(batch);
        f.skin.putShort(164 + 10, (short) 1);
        f.skin.putShort(164 + 16, (short) 1);
        f.skin.putInt(36, 2);
        var parsed = f.parse();
        assertEquals(2, parsed.rest().batches().size());
        assertEquals(4, parsed.model().materials().get(0).blendMode());
        assertEquals(0, parsed.model().materials().get(1).blendMode());

        var world = worldBinding(f);
        var surfaces = world.appearance().surfaces();
        assertEquals(1, surfaces.size(), world.issues().toString());
        assertEquals(MeshAppearance.Blend.OPAQUE, surfaces.getFirst().material().blend());
        assertEquals(1, world.appearance().textures().size(), "only the opaque texture is decoded");

        // With the opaque texture missing, the additive batch draws the section.
        var fallback = worldBinding(f, 9002);
        assertEquals(1, fallback.appearance().surfaces().size(), fallback.issues().toString());
        assertEquals(
                MeshAppearance.Blend.ADDITIVE,
                fallback.appearance().surfaces().getFirst().material().blend());
    }

    @Test
    void anAdditiveBindingWhoseWeightCannotBeReadIsNotSaved() throws Exception {
        var f = glow();
        weightTrack(f, 0);
        // Track 0's sequence slices point past the model: its weight facts cannot be read.
        int track = f.body.getInt(88 + 4);
        f.body.putInt(track + 16, 0x7fff_0000);
        var both = worldBindings(f, 2);
        for (var world : both) {
            assertNotNull(world.appearance(), world.message());
            assertTrue(
                    world.appearance().surfaces().isEmpty(), "an undecided weight draws nothing");
            assertTrue(
                    world.issues().stream().anyMatch(s -> s.startsWith("Static opacity facts")),
                    world.issues().toString());
        }
        assertFalse(both.get(0).restored());
        assertFalse(both.get(1).restored(), "not saved, so it is reassessed");

        // The same model with readable facts is saved.
        var readable = glow();
        weightTrack(readable, 0);
        var saved = worldBindings(readable, 2);
        assertEquals(
                1, saved.get(0).appearance().surfaces().size(), saved.get(0).issues().toString());
        assertFalse(saved.get(0).restored());
        assertTrue(saved.get(1).restored(), "decided bindings are saved");
    }

    private static boolean blended(NativeMaterialFixture f, boolean withFacts) {
        var parsed = f.parse();
        return DisplayModelService.worldBlendedBatch(
                parsed.model(),
                parsed.rest().batches().getFirst(),
                withFacts ? parsed.appearance() : null);
    }

    /** An alpha-blended batch: Combiners_Opaque / Diffuse_T1, blend 2, with the material flags. */
    private static NativeMaterialFixture veil(int materialFlags) throws Exception {
        return new NativeMaterialFixture(274, 16, 0, 1, materialFlags, 2);
    }

    @Test
    void blendedBatchesDrawOnlyWithAWeightThatStaysFull() throws Exception {
        var none = veil(0);
        noWeight(none);
        assertFalse(world(none), "not an opaque or alpha-keyed batch");
        assertFalse(additive(none, true), "not additive");
        assertTrue(blended(none, true), "no weight track");
        assertTrue(blended(none, false), "no weight track needs no facts");

        var steady = veil(0);
        weightTrack(steady, 0);
        assertTrue(blended(steady, true), "a weight track without keys never fades");
        assertFalse(blended(steady, false), "a weight track without facts is not drawn");

        var fading = veil(0);
        weightTrack(fading, 1);
        assertFalse(blended(fading, true), "a weight key below full scales the alpha");

        for (int blend : new int[] {0, 1, 3, 4, 5, 6, 7}) {
            var other = new NativeMaterialFixture(274, 16, 0, 1, 0, blend);
            noWeight(other);
            assertFalse(blended(other, true), "blend " + blend);
        }
        var env = new NativeMaterialFixture(274, 16, 0x80, 1, 0, 2);
        noWeight(env);
        assertFalse(blended(env, true), "Diffuse_Env");
        var layered = veil(0);
        noWeight(layered);
        layered.skin.putShort(152, (short) 1);
        assertFalse(blended(layered, true), "material layer 1");
    }

    @Test
    void aBlendedOnlySectionBindsABlendedSurfaceLitUnlessTheMaterialIsUnlit() throws Exception {
        var lit = veil(0);
        noWeight(lit);
        var world = worldBinding(lit);
        var surfaces = world.appearance().surfaces();
        assertEquals(1, surfaces.size(), world.issues().toString());
        var material = surfaces.getFirst().material();
        assertEquals(MeshAppearance.Combiner.DIFFUSE, material.combiner());
        assertEquals(MeshAppearance.Blend.BLENDED, material.blend());
        assertEquals(MeshAppearance.Lighting.LIT, material.lighting());
        assertEquals(1, material.stages().size(), "texture 1 only");
        assertTrue(world.issues().contains(DisplayModelService.WORLD_NOTE));

        var unlit = veil(0x11);
        noWeight(unlit);
        var glowing = worldBinding(unlit).appearance().surfaces().getFirst().material();
        assertEquals(MeshAppearance.Blend.BLENDED, glowing.blend());
        assertEquals(MeshAppearance.Lighting.UNLIT, glowing.lighting());

        var fading = veil(0);
        weightTrack(fading, 1);
        assertTrue(worldBinding(fading).appearance().surfaces().isEmpty(), "fading: no surface");
    }

    @Test
    void opaqueAndAdditiveBatchesClaimTheirSectionAheadOfAnEarlierBlendedOne() throws Exception {
        for (int second : new int[] {0, 4}) {
            var f = veil(0);
            noWeight(f);
            // Material 1 (opaque or unlit additive); a second batch on section 0 with material 1
            // and texture combo 1.
            int materials = f.body.position();
            f.declaration(112, 2, materials);
            f.body.putShort((short) 0).putShort((short) 2);
            f.body.putShort((short) (second == 4 ? 0x11 : 0)).putShort((short) second);
            byte[] batch = Arrays.copyOfRange(f.skin.array(), 140, 164);
            f.skin.position(164);
            f.skin.put(batch);
            f.skin.putShort(164 + 10, (short) 1);
            f.skin.putShort(164 + 16, (short) 1);
            f.skin.putInt(36, 2);
            var parsed = f.parse();
            assertEquals(2, parsed.model().materials().get(0).blendMode());
            assertEquals(second, parsed.model().materials().get(1).blendMode());

            var world = worldBinding(f);
            var surfaces = world.appearance().surfaces();
            assertEquals(1, surfaces.size(), world.issues().toString());
            assertEquals(
                    second == 0 ? MeshAppearance.Blend.OPAQUE : MeshAppearance.Blend.ADDITIVE,
                    surfaces.getFirst().material().blend());
            assertEquals(1, world.appearance().textures().size(), "only texture 9002 is decoded");

            // With texture 9002 missing, the blended batch draws the section.
            var fallback = worldBinding(f, 9002);
            assertEquals(1, fallback.appearance().surfaces().size(), fallback.issues().toString());
            assertEquals(
                    MeshAppearance.Blend.BLENDED,
                    fallback.appearance().surfaces().getFirst().material().blend());
        }
    }
}
