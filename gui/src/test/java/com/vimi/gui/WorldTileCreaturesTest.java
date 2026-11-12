package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless checks of static creature models for spawn points: the BASIC conversion and its
 * refusals, tile ownership, the shared objects budget, caching per revision and failure isolation.
 * Authored meshes only; no game data.
 */
public final class WorldTileCreaturesTest {
    private WorldTileCreaturesTest() {}

    /** Native x and y in (-533.33, 0] belong to tile 32,32. */
    private static final WorldFormats.Tile TILE = new WorldFormats.Tile(32, 32, 1, 0, 0);

    public static void main(String[] args) throws Exception {
        conversionKeepsOpaqueSurfacesAndDropsCards();
        conversionRefusesWhatItCannotDraw();
        texturesReduceToPowerOfTwoOpaqueImages();
        alphaKeyedSurfacesDrawAsCutoutKeepingAlpha();
        additiveSurfacesDrawInTheAdditivePassUnlit();
        blendedSurfacesDrawInTheBlendedPassLitOrUnlit();
        defaultGeosetsDrawWithoutCreatureGeosetData();
        aDenseNeighbourNoLongerStarvesTheTilesOwnSpawns();
        busyOrClosedPreviewsCostNoBudget();
        creaturesStandInTheirIdlePoseOrSayWhyNot();
        billboardTrianglesAreLeftOutOfTheWorldStandAndDisclosed();
        unreadableStandSequencesAreAskedAgainButUnsupportedOnesAreKept();
        cutoutReductionKeepsThinStrands();
        displayGeosetRowsApplyOnlyWhenTheModelRecordsGeosetData();
        appendDrawsOnlyQualifiedSpawnsOfTheTile();
        buildingsKeepTheirBudgetFirst();
        cachesPerRevisionAndRetriesFailures();
        cancellationPropagatesAndSourceFailuresAreNotes();
        statusDisclosure();
        theWorldAsksForItsOwnBinding();
        humanoidNpcsDrawWholeOrSayWhy();
        capsStopLookupsAndForeignCancellationIsAFailure();
        humanoidDisplaysSkipTheirPreview();
        aHumanoidDisplayWithoutItsModelFileIsDrawnAsAnNpc();
        failedIndexLoadsBackOff();
        conversionChecksVertexValuesAndLightsMissingNormals();
        distinctModelsStopAtTheTileTextureBudget();
        theModelCacheIsBoundedByBytes();
        tooLargeModelsAreSkippedAndSmallerOnesStillFit();
        onlyNpcConversionsUseTheNpcSide();
        npcImagesUseTheSmallerSide();
        aTileHandsItsUnchosenNpcsToTheChooserTogether();
        BridgeAssembly.run();
        System.out.println("WorldTileCreaturesTest passed");
    }

    /**
     * The production previews give the World the component's World binding (#361), not the
     * preview's reviewed profiles.
     */
    private static void theWorldAsksForItsOwnBinding() {
        var forPreview = preview(7, 0L, 0L, appearance(false));
        var forWorld = preview(7, 0L, 0L, appearance(true));
        var models = new com.vibeminer.concepts.models.BuildDisplayModels() {
            @Override
            public java.util.concurrent.CompletableFuture<DisplayModelTypes.Preview> preview(
                    long displayId, Cancellation token) {
                return java.util.concurrent.CompletableFuture.completedFuture(forPreview);
            }

            @Override
            public java.util.concurrent.CompletableFuture<DisplayModelTypes.Preview> worldPreview(
                    long displayId, Cancellation token) {
                return java.util.concurrent.CompletableFuture.completedFuture(forWorld);
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        try (var previews = new WorldTileCreatures.DisplayPreviews(() -> models, () -> "r")) {
            require(previews.preview(7, Cancellation.NONE) == forWorld,
                    "The World draws the World binding, not the preview's");
        }
    }

    private static void conversionKeepsOpaqueSurfacesAndDropsCards() {
        var converted = WorldTileCreatures.convert(preview(7, 0L, 0L, appearance(true)), Cancellation.NONE);
        require(converted.model() != null, "An opaque creature converts: " + converted.detail());
        var model = converted.model();
        require(model.indices().length == 6, "The opaque and the alpha-keyed triangle remain");
        require(model.vertexCount() == 3, "Vertices are compacted to the drawn triangles");
        require(model.omittedSurfaces() == 2, "Unbound and card sections are counted");
        require(java.util.Arrays.equals(model.ranges(), new int[] {0, 3, 0, 3, 3, 1}),
                "One range per drawn section, each binding its own image");
        require(java.util.Arrays.equals(model.blends(), new int[] {WorldTileCreatures.OPAQUE,
                WorldTileCreatures.CUTOUT}), "The alpha-keyed section is a cutout range");
        require(model.images().size() == 2, "One texture used opaque and cutout carries one image per class");
        float[] v = model.vertices();
        require(v[0] == 0 && v[1] == 0 && v[2] == 0 && v[5] == 1 && v[6] == 0 && v[7] == 0,
                "Position, normal and uv are kept in M2 model space");
    }

    private static void conversionRefusesWhatItCannotDraw() {
        require(WorldTileCreatures.convert(preview(7, 5L, 0L, appearance(true)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.CHARACTER_APPEARANCE, "Extended display info is refused");
        require(WorldTileCreatures.convert(preview(7, null, 0L, appearance(true)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.CHARACTER_APPEARANCE, "Unknown extended display info is refused");
        require(WorldTileCreatures.convert(preview(7, 0L, 3L, null, geosets()), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED, "Recorded geoset data with unknown rows is refused");
        require(WorldTileCreatures.convert(preview(7, 0L, 0L, unboundOnly()), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.NO_OPAQUE_SURFACE, "No drawable textured surface is refused");
        var nothing = WorldTileCreatures.convert(preview(7, 0L, 0L, mesh(102, 103)), Cancellation.NONE);
        require(nothing.refusal() == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED
                        && nothing.detail().equals("the model has no section 0 or x01 section to draw"),
                "A model with neither section 0 nor any x01 has nothing to draw: " + nothing.detail());
        var missing = new DisplayModelTypes.Preview(DisplayModelTypes.State.UNAVAILABLE, null, null, Set.of(),
                List.of(), false, "Model read is ABSENT");
        var refused = WorldTileCreatures.convert(missing, Cancellation.NONE);
        require(refused.refusal() == WorldTileCreatures.Count.MODEL_UNAVAILABLE
                && refused.detail().equals("Model read is ABSENT"), "An unavailable preview keeps its reason");
    }

    private static void texturesReduceToPowerOfTwoOpaqueImages() {
        int[] argb = new int[300 * 6];
        for (int i = 0; i < argb.length; i++) argb[i] = 0x10ff8040;
        var image = WorldTileCreatures.reduce(new MeshAppearance.Texture(300, 6, argb), Cancellation.NONE);
        require(image.width() == 256 && image.height() == 4, "Sides become powers of two of at most 256");
        var large = WorldTileCreatures.reduce(new MeshAppearance.Texture(1024, 512, new int[1024 * 512]),
                Cancellation.NONE);
        require(large.width() == 256 && large.height() == 256, "Each side is capped at 256");
        require((image.rgba()[0] & 255) == 0xff && (image.rgba()[1] & 255) == 0x80
                && (image.rgba()[2] & 255) == 0x40 && (image.rgba()[3] & 255) == 255,
                "Box filtering keeps a flat colour and forces alpha opaque");
        var small = WorldTileCreatures.reduce(new MeshAppearance.Texture(2, 2,
                new int[] {0xff000000, 0xffffffff, 0xff000000, 0xffffffff}), Cancellation.NONE);
        require(small.width() == 2 && (small.rgba()[4] & 255) == 255, "Small images are kept exactly");
        require(image.rgba().length
                        == com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(256, 4) * 4
                        && small.rgba().length == (4 + 1) * 4,
                "Creature images carry their generated mip chain");
    }

    /**
     * The creature World binding's unlit additive surfaces (#361) reach the objects file as
     * additive, unlit ranges whose image keeps its alpha as a blended one; humanoid NPC appearances
     * keep leaving additive surfaces out.
     */
    private static void additiveSurfacesDrawInTheAdditivePassUnlit() {
        var converted = WorldTileCreatures.convert(preview(7, 0L, 0L, additiveOnly()), Cancellation.NONE);
        require(converted.model() != null, "An additive-only creature draws: " + converted.detail());
        var model = converted.model();
        require(java.util.Arrays.equals(model.blends(),
                        new int[] {WorldTileCreatures.ADDITIVE | WorldTileCreatures.UNLIT}),
                "The additive section is an unlit additive range: " + java.util.Arrays.toString(model.blends()));
        require(com.vibeminer.rendering.world.WorldObjectPicking.kind(model.blends()[0])
                        == com.vibeminer.rendering.world.WorldObjectPicking.ADDITIVE,
                "The range kind is the objects file's additive kind");
        require(model.omittedSurfaces() == 0, "Nothing is left out");
        // Card triangles on an additive surface are still dropped (their own section is omitted).
        var cards = WorldTileCreatures.convert(preview(7, 0L, 0L, appearance(true)), Cancellation.NONE).model();
        require(cards.omittedSurfaces() == 2 && cards.blends().length == 2,
                "A rigid card stays out even on an additive surface");
        // Opaque and cutout surfaces of a model with an additive part keep their classes, and one
        // texture used opaque and additive carries one image per class.
        var mixed = WorldTileCreatures.convert(preview(7, 0L, 0L, opaqueAndAdditive()), Cancellation.NONE).model();
        require(mixed != null && java.util.Arrays.equals(mixed.blends(), new int[] {WorldTileCreatures.OPAQUE,
                WorldTileCreatures.ADDITIVE | WorldTileCreatures.UNLIT}), "Opaque then additive ranges");
        require(mixed.images().size() == 2, "One image per material class");
        require((mixed.images().get(0).rgba()[3] & 255) == 255
                        && (mixed.images().get(1).rgba()[3] & 255) == 0x80,
                "The opaque image forces alpha, the additive one keeps it");
        // Humanoid NPC appearances do not take the creature rule's additive surfaces.
        var npc = WorldTileCreatures.surfaces(additiveOnly(), Set.of(0), Cancellation.NONE);
        require(npc.refusal() == WorldTileCreatures.Count.NO_OPAQUE_SURFACE,
                "NPC appearances leave additive surfaces out");
        // A blended image keeps its alpha, colour weighted by alpha, with no coverage matching.
        int[] edge = new int[512];
        edge[255] = 0xffffffff;
        var reduced = WorldTileCreatures.reduce(new MeshAppearance.Texture(512, 1, edge),
                WorldTileCreatures.BLENDED, Cancellation.NONE);
        byte[] r = reduced.rgba();
        require((r[127 * 4] & 255) == 255 && (r[127 * 4 + 3] & 255) == 128 && (r[3] & 255) == 0,
                "Blended images keep the plain alpha mean");
        var builder = WorldStreamTileWriter.Exterior.builder();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))), () -> "r1",
                (npcId, token) -> qualified(7, null), (display, token) -> preview(display, 0L, 0L, additiveOnly()));
        require(creatures.append(builder, 0, TILE, Cancellation.NONE).drawn() == 1, "The additive creature is placed");
    }

    /**
     * Blended surfaces (the creature World binding's blend 2, #361) draw as blended ranges in the
     * World objects' sorted blended pass, unlit only when the binding says so.
     */
    private static void blendedSurfacesDrawInTheBlendedPassLitOrUnlit() {
        var lit = WorldTileCreatures.convert(preview(7, 0L, 0L, blendedAndAdditive(MeshAppearance.Lighting.LIT)),
                Cancellation.NONE);
        require(lit.model() != null, "A blended creature draws: " + lit.detail());
        var model = lit.model();
        require(java.util.Arrays.equals(model.blends(), new int[] {WorldTileCreatures.BLENDED,
                        WorldTileCreatures.ADDITIVE | WorldTileCreatures.UNLIT}),
                "Blended then additive ranges: " + java.util.Arrays.toString(model.blends()));
        require(com.vibeminer.rendering.world.WorldObjectPicking.kind(model.blends()[0])
                        == com.vibeminer.rendering.world.WorldObjectPicking.BLENDED
                        && com.vibeminer.rendering.world.WorldObjectPicking.validClass(model.blends()[0]),
                "The range kind is the objects file's blended kind");
        require(model.images().size() == 1 && (model.images().get(0).rgba()[3] & 255) == 0x80,
                "Blended and additive uses of one texture share one blended image, alpha kept");
        var unlit = WorldTileCreatures.convert(preview(7, 0L, 0L,
                blendedAndAdditive(MeshAppearance.Lighting.UNLIT)), Cancellation.NONE).model();
        require(unlit != null && unlit.blends()[0] == (WorldTileCreatures.BLENDED | WorldTileCreatures.UNLIT)
                        && com.vibeminer.rendering.world.WorldObjectPicking.validClass(unlit.blends()[0]),
                "An unlit blended surface is an unlit blended range");
        // Humanoid NPC appearances do not take the creature rule's blended surfaces.
        var npc = WorldTileCreatures.surfaces(blendedAndAdditive(MeshAppearance.Lighting.LIT), Set.of(0),
                Cancellation.NONE);
        require(npc.refusal() == WorldTileCreatures.Count.NO_OPAQUE_SURFACE,
                "NPC appearances leave blended surfaces out");
        var builder = WorldStreamTileWriter.Exterior.builder();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))), () -> "r1",
                (npcId, token) -> qualified(7, null),
                (display, token) -> preview(display, 0L, 0L, blendedAndAdditive(MeshAppearance.Lighting.LIT)));
        require(creatures.append(builder, 0, TILE, Cancellation.NONE).drawn() == 1, "The blended creature is placed");
    }

    /** Part 0 blended (with the given lighting) and part 1 unlit additive, on one texture. */
    static MeshAppearance blendedAndAdditive(MeshAppearance.Lighting lighting) {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2, 0, 2, 1},
                List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 3, 3)));
        var stage = List.of(new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, true, true));
        var blended = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE, stage, lighting,
                MeshAppearance.Blend.BLENDED);
        var additive = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE, stage,
                MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        return new MeshAppearance(mesh, new float[6],
                List.of(new MeshAppearance.Surface(0, blended), new MeshAppearance.Surface(1, additive)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {0x80ffffff})));
    }

    /** Part 0 opaque and part 1 unlit additive, both on one half-transparent texture. */
    static MeshAppearance opaqueAndAdditive() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2, 0, 2, 1},
                List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 3, 3)));
        var additive = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE,
                List.of(new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, true, true)),
                MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        return new MeshAppearance(mesh, new float[6],
                List.of(new MeshAppearance.Surface(0, 0, true, true, false), new MeshAppearance.Surface(1, additive)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {0x80ffffff})));
    }

    private static void alphaKeyedSurfacesDrawAsCutoutKeepingAlpha() {
        // Left half transparent black, right half opaque white: a 4 x 1 texture reduced to 2 x 1
        // keeps the alpha and does not darken the opaque texels with the transparent ones.
        var texture = new MeshAppearance.Texture(4, 1, new int[] {0x00000000, 0x00000000, 0xffffffff, 0x80ffffff});
        var cutout = WorldTileCreatures.reduce(texture, WorldTileCreatures.CUTOUT, Cancellation.NONE);
        byte[] rgba = cutout.rgba();
        require(cutout.width() == 4 && (rgba[3] & 255) == 0 && (rgba[11] & 255) == 255
                && (rgba[15] & 255) == 0x80, "Cutout images keep their alpha");
        int[] edge = new int[512];
        edge[255] = 0xffffffff;
        var reduced = WorldTileCreatures.reduce(new MeshAppearance.Texture(512, 1, edge),
                WorldTileCreatures.CUTOUT, Cancellation.NONE);
        byte[] r = reduced.rgba();
        // Texel 127 averages source 254 (transparent black) and 255 (opaque white).
        require(reduced.width() == 256 && (r[127 * 4] & 255) == 255 && (r[127 * 4 + 3] & 255) == 128,
                "Colour is weighted by alpha, alpha is the plain mean");
        var opaque = WorldTileCreatures.reduce(texture, WorldTileCreatures.OPAQUE, Cancellation.NONE);
        require((opaque.rgba()[3] & 255) == 255, "Opaque images still force alpha");
        // The cutout range reaches the objects file with its class.
        var model = WorldTileCreatures.convert(preview(7, 0L, 0L, cutoutOnly()), Cancellation.NONE).model();
        require(model != null && java.util.Arrays.equals(model.blends(), new int[] {WorldTileCreatures.CUTOUT}),
                "A model with only alpha-keyed surfaces draws as cutout");
        var builder = WorldStreamTileWriter.Exterior.builder();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> preview(display, 0L, 0L, cutoutOnly()));
        require(creatures.append(builder, 0, TILE, Cancellation.NONE).drawn() == 1, "The cutout creature is placed");
        require(builder.build().draws() == 1, "Its one cutout range is drawn");
    }

    private static void defaultGeosetsDrawWithoutCreatureGeosetData() {
        var converted = WorldTileCreatures.convert(preview(7, 0L, 0L, geosets()), Cancellation.NONE);
        var model = converted.model();
        require(model != null, "Default geosets draw: " + converted.detail());
        // Parts 0 (id 0) and 2 (id 101, group 1's x01) draw; part 1 (id 100) is hidden and not
        // counted as an omitted surface.
        require(java.util.Arrays.equals(model.ranges(), new int[] {0, 3, 0, 3, 3, 0})
                && model.omittedSurfaces() == 0, "Only section 0 and x01 sections draw");
        require(visible(0L, Map.of(), geosets()).equals(java.util.Set.of(0, 101)), "Default: 0 and 101");
        // A group without x01 (optional pieces from x02 on) draws nothing; it is not refused.
        require(visible(0L, Map.of(), mesh(0, 102, 103, 201, 202)).equals(java.util.Set.of(0, 201)),
                "A group without x01 is hidden, another group draws its x01");
        require(visible(0L, Map.of(), hiddenGeosets()).equals(java.util.Set.of(101)),
                "A model without group 0 draws its x01 sections");
        // Group 0 draws only section 0; its other sections stay hidden.
        require(visible(0L, Map.of(), mesh(0, 1, 101)).equals(java.util.Set.of(0, 101)),
                "Group 0 sections other than 0 are hidden");
        require(refusal(0L, Map.of(), mesh(1, 101)).contains("geoset group 0 has no section 0"),
                "Group 0 without section 0 is refused");
        var unreadable = WorldTileCreatures.convert(preview(7, 0L, null, geosets()), Cancellation.NONE);
        require(unreadable.refusal() == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED
                && unreadable.detail().contains("could not be read"), "An unreadable CreatureGeosetDataID is refused");
        var unknown = WorldTileCreatures.convert(preview(7, 0L, 5L, null, geosets()), Cancellation.NONE);
        require(unknown.refusal() == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED
                && unknown.detail().contains("unknown"), "Unknown display geoset rows are refused when they apply");
    }

    private static void displayGeosetRowsApplyOnlyWhenTheModelRecordsGeosetData() {
        // geosets(): parts 0, 100 and 101 (group 1 has x00 and x01).
        // ID > 0: the display's row chooses value 1 for group 1 (GeosetIndex 0): 0 and 101 draw.
        var chosen = WorldTileCreatures.convert(preview(7, 0L, 5L, Map.of(1, 1), geosets()), Cancellation.NONE);
        require(chosen.model() != null && java.util.Arrays.equals(chosen.model().ranges(), new int[] {0, 3, 0, 3, 3, 0})
                && chosen.model().omittedSurfaces() == 0, "The display's chosen variant replaces x00: " + chosen.detail());
        require(visible(5L, Map.of(1, 1), geosets()).equals(java.util.Set.of(0, 101)), "Chosen: 0 and 101");
        // ID > 0 without a row for group 1: the group is hidden, not drawn as x00; group 0 stays.
        require(visible(5L, Map.of(), geosets()).equals(java.util.Set.of(0)), "A group 1-8 without a row is hidden");
        require(WorldTileCreatures.convert(preview(7, 0L, 5L, Map.of(), geosets()), Cancellation.NONE).model() != null,
                "A display without rows still draws group 0");
        // ID > 0: a row for group 9 replaces its default; group 9 without a row takes x01.
        var high = mesh(0, 900, 901);
        require(visible(5L, Map.of(9, 1), high).equals(java.util.Set.of(0, 901)), "A row for group 9 chooses 901");
        require(visible(5L, Map.of(), high).equals(java.util.Set.of(0, 901)), "Group 9 without a row takes x01");
        // ID > 0: unknown rows, a missing chosen section or conflicting rows are refused.
        require(refusal(5L, null, geosets()).contains("unknown"), "Unknown rows are refused");
        require(refusal(5L, Map.of(1, 7), geosets()).contains("geoset 107 chosen by the display is not in the model"),
                "A missing chosen section is refused");
        require(refusal(5L, Map.of(1, DisplayModelTypes.Reference.CONFLICTING_GEOSET), geosets()).contains("conflict"),
                "Conflicting rows are refused");
        // ID > 0: a row for a group the model does not have changes nothing.
        require(visible(5L, Map.of(4, 2, 1, 0), geosets()).equals(java.util.Set.of(0, 100)), "Rows for absent groups are ignored");
        // ID == 0: display rows are ignored, every group takes the default (unknown rows do not matter).
        require(visible(0L, Map.of(1, 0), geosets()).equals(java.util.Set.of(0, 101)), "Rows are ignored when the ID is 0");
        require(visible(0L, null, geosets()).equals(java.util.Set.of(0, 101)), "Unknown rows do not matter when the ID is 0");
        require(visible(0L, Map.of(1, DisplayModelTypes.Reference.CONFLICTING_GEOSET), geosets())
                .equals(java.util.Set.of(0, 101)), "With the ID 0 even conflicting rows are ignored");
        // ID > 0: group 8 is the last hidden group without a row (801 and 899); a row shows it.
        var eight = mesh(0, 801, 899);
        require(visible(5L, Map.of(), eight).equals(java.util.Set.of(0)), "Group 8 without a row is hidden");
        require(visible(5L, Map.of(8, 99), eight).equals(java.util.Set.of(0, 899)), "A row for group 8 chooses 899");
        // ID > 0: a group of 9 or more without a row takes the default: x01, or nothing.
        require(visible(5L, Map.of(), mesh(0, 901)).equals(java.util.Set.of(0, 901)), "Group 9 without a row draws 901");
        require(visible(5L, Map.of(), mesh(0, 902)).equals(java.util.Set.of(0)), "Group 9 without x01 draws nothing");
        // ID > 0: a row choosing a 9+ group's x00 draws only that section, not also its x01.
        require(visible(5L, Map.of(9, 0), mesh(0, 900, 901)).equals(java.util.Set.of(0, 900)),
                "A chosen group 9 draws only its chosen section");
        // ID > 0: when only the default hides everything (an unlisted 9+ group without x01), the
        // reason names the default, not the rows.
        var defaultHid = WorldTileCreatures.convert(preview(7, 0L, 5L, Map.of(), mesh(902)), Cancellation.NONE);
        require(defaultHid.refusal() == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED
                        && defaultHid.detail().equals("the model has no section 0 or x01 section to draw"),
                "The default, not the rows, hid it: " + defaultHid.detail());
        // ID > 0: when the rows hide every section there is nothing to draw: a visibility refusal.
        var allHidden = WorldTileCreatures.convert(preview(7, 0L, 5L, Map.of(), hiddenGeosets()), Cancellation.NONE);
        require(allHidden.refusal() == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED
                && allHidden.detail().equals("every geoset is hidden by the display's rows"),
                "All sections hidden is a visibility refusal, not a material one: " + allHidden.detail());
    }

    private static java.util.Set<Integer> visible(Long geosets, Map<Integer, Integer> rows, MeshAppearance appearance) {
        var result = WorldTileCreatures.visibleGeosets(preview(7, 0L, geosets, rows, appearance).reference(),
                appearance.mesh());
        require(result.refusal() == null, "Unexpected refusal: " + result.refusal());
        return result.ids();
    }

    private static String refusal(Long geosets, Map<Integer, Integer> rows, MeshAppearance appearance) {
        var result = WorldTileCreatures.visibleGeosets(preview(7, 0L, geosets, rows, appearance).reference(),
                appearance.mesh());
        require(result.refusal() != null, "Expected a refusal, drew " + result.ids());
        return result.refusal();
    }

    /** One opaque one-triangle part per geoset ID. */
    private static MeshAppearance mesh(int... ids) {
        var parts = new ArrayList<PreviewMesh.Part>();
        var surfaces = new ArrayList<MeshAppearance.Surface>();
        int[] indices = new int[ids.length * 3];
        for (int i = 0; i < ids.length; i++) {
            parts.add(new PreviewMesh.Part(ids[i], i * 3, 3));
            surfaces.add(new MeshAppearance.Surface(i, 0, true, true, false));
            indices[i * 3 + 1] = 1;
            indices[i * 3 + 2] = 2;
        }
        return new MeshAppearance(new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, indices, parts),
                new float[6], surfaces, List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    private static void cutoutReductionKeepsThinStrands() {
        // Scattered one-texel opaque strands, 1/8 of the texels, reduced 1024 -> 256: a 4 x 4 box
        // holds about two of them, alpha about 32, so plain averaging fails almost every texel.
        int side = 1024;
        int[] argb = new int[side * side];
        var random = new java.util.Random(7);
        for (int i = 0; i < argb.length; i++) if (random.nextInt(8) == 0) argb[i] = 0xff806040;
        var image = WorldTileCreatures.reduce(new MeshAppearance.Texture(side, side, argb),
                WorldTileCreatures.CUTOUT, Cancellation.NONE);
        require(image.width() == 256, "Reduced to 256");
        double coverage = com.vibeminer.rendering.world.ObjectTextureMips.coverage(image.rgba(), 0, 256 * 256);
        require(Math.abs(coverage - 0.125) < 0.03, "The top level keeps the source coverage: " + coverage);
        var opaque = WorldTileCreatures.reduce(new MeshAppearance.Texture(side, side, argb),
                WorldTileCreatures.OPAQUE, Cancellation.NONE);
        require((opaque.rgba()[3] & 255) == 255, "Opaque images are not rescaled");
    }


    private static void aDenseNeighbourNoLongerStarvesTheTilesOwnSpawns() {
        // 600 points just across the tile's +x edge (tile 31) are nearer the tile centre than the
        // tile's own corner point; the 512-point query used to fill up with them first.
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 600; i++) rows.add(WorldSpawnOverlayTest.spawn(2, i + 1, 5 + i * 0.01, -266, 10.0));
        rows.add(WorldSpawnOverlayTest.spawn(1, 1, -520, -520, 10.0));
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> preview(display, 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 1 && summary.placed().getFirst().spawn().npcId() == 1,
                "The tile's own point is drawn next to a dense neighbour");
        require(summary.note() == null, "Neighbour points are not reported as beyond the query limit");
    }

    private static void busyOrClosedPreviewsCostNoBudget() {
        // More displays than the preview budget, whose previews are all refused busy (bare or
        // wrapped): every point is counted as busy, none is capped by the budget and nothing is cached.
        int displays = WorldTileCreatures.TILE_PREVIEWS + 4;
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < displays; i++) rows.add(WorldSpawnOverlayTest.spawn(i + 1, 1, -100 - i, -100, 10.0));
        var calls = new AtomicInteger();
        var busy = new java.util.concurrent.atomic.AtomicBoolean(true);
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified((int) npc, null), (display, token) -> {
                    int call = calls.incrementAndGet();
                    if (busy.get()) {
                        var refusal = new DisplayModelTypes.ServiceUnavailable("Display previews busy",
                                call % 3 == 0, null);
                        if (call % 2 == 0) throw new java.util.concurrent.CompletionException(refusal);
                        throw refusal;
                    }
                    return preview(display, 0L, 0L, appearance(true));
                });
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.count(WorldTileCreatures.Count.PREVIEWS_BUSY) == displays
                        && summary.count(WorldTileCreatures.Count.CAPPED) == 0
                        && summary.count(WorldTileCreatures.Count.FAILED) == 0,
                "Busy or closed previews are their own count and take no preview budget");
        busy.set(false);
        var later = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(later.drawn() == WorldTileCreatures.TILE_PREVIEWS
                        && calls.get() == displays + WorldTileCreatures.TILE_PREVIEWS,
                "A busy refusal is not cached: the next tile reads the previews again");
        require(WorldTileCreatures.busy(new RuntimeException(new java.util.concurrent.CompletionException(
                new DisplayModelTypes.ServiceUnavailable("closed", true, null))))
                && !WorldTileCreatures.busy(new IllegalStateException("Display previews busy")),
                "Only the component's own busy/closed refusal counts as busy");
        // 300 spawns of one display: a busy preview is asked once per tile, not once per spawn.
        var same = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 300; i++) same.add(WorldSpawnOverlayTest.spawn(1, i + 1, -100 - i * 0.5, -120, 10.0));
        var asked = new AtomicInteger();
        var one = new WorldTileCreatures(token -> index(same), () -> "r1", (npc, token) -> qualified(7, null),
                (display, token) -> {
                    asked.incrementAndGet();
                    throw new DisplayModelTypes.ServiceUnavailable("Display previews busy", false, null);
                });
        var crowded = one.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(asked.get() == 1 && crowded.count(WorldTileCreatures.Count.PREVIEWS_BUSY) == 300,
                "A busy display is not resubmitted for every spawn: " + asked.get());
    }

    /** One opaque triangle at z 0 and its one-bone Stand sequence that lifts it by 5. */
    static MeshAppearance standing() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /** Two opaque one-triangle parts: part 0 (vertices 0-2) and part 1 (vertices 3-5). */
    static MeshAppearance twoParts() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 3, 0, 0, 4, 0, 0, 3, 1, 0},
                new int[] {0, 1, 2, 3, 4, 5}, List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 3, 3)));
        return new MeshAppearance(mesh, new float[12], List.of(new MeshAppearance.Surface(0, 0, true, true, false),
                new MeshAppearance.Surface(1, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /**
     * Part 0 (vertices 0-2) on geoset 0, part 1 (vertices 0-2) on geoset 101, the default of group
     * 1, and part 2 (vertices 3-5) on geoset 102, which the default hides.
     */
    static MeshAppearance hiddenPart() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 3, 0, 0, 4, 0, 0, 3, 1, 0},
                new int[] {0, 1, 2, 0, 2, 1, 3, 4, 5}, List.of(new PreviewMesh.Part(0, 0, 3),
                        new PreviewMesh.Part(101, 3, 3), new PreviewMesh.Part(102, 6, 3)));
        return new MeshAppearance(mesh, new float[12], List.of(new MeshAppearance.Surface(0, 0, true, true, false),
                new MeshAppearance.Surface(1, 0, true, true, false), new MeshAppearance.Surface(2, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    private static void billboardTrianglesAreLeftOutOfTheWorldStandAndDisclosed() throws Exception {
        var appearance = twoParts();
        // A vertex weighted to a camera-facing bone leaves out its triangle; the emptied surface is
        // an omitted surface, as one made only of rigid cards is.
        var converted = WorldTileCreatures.convert(preview(7, 0L, 0L, appearance), new int[] {4}, Cancellation.NONE);
        require(converted.model() != null && converted.model().billboardTriangles() == 1
                && converted.model().omittedSurfaces() == 1 && converted.model().ranges().length == 3,
                "One billboard triangle is left out with its surface");
        require(WorldTileCreatures.convert(preview(7, 0L, 0L, appearance), Cancellation.NONE).model()
                .billboardTriangles() == 0, "Nothing is left out without a list");
        // Every drawable triangle on camera-facing bones: refused with its own reason, as World
        // objects refuse such a model.
        var all = WorldTileCreatures.convert(preview(7, 0L, 0L, appearance), new int[] {0, 4}, Cancellation.NONE);
        require(all.refusal() == WorldTileCreatures.Count.ALL_BILLBOARD
                && all.detail().equals("every drawable triangle is weighted to a camera-facing bone"),
                "An all-billboard Stand is its own refusal: " + all.refusal() + " " + all.detail());
        // Without billboard triangles an empty model is still a material refusal.
        require(WorldTileCreatures.convert(preview(7, 0L, 0L, unboundOnly()), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.NO_OPAQUE_SURFACE, "Material refusals keep their bucket");
        // The World asks the World profile: the preview's refusal does not matter there.
        var world = new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.AVAILABLE, null, lift(appearance),
                "Stand", new int[] {4});
        var creatures = new WorldTileCreatures(token -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(1, 2, -110, -100, 10.0))), () -> "r1",
                (npc, token) -> qualified(7, null), new WorldTileCreatures.Previews() {
                    public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                        return WorldTileCreaturesTest.preview(display, 0L, 0L, appearance);
                    }

                    public DisplayModelTypes.Idle idle(long display, Cancellation token) {
                        return new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.UNSUPPORTED, null, null,
                                "Billboard bone 1 has child bones");
                    }

                    public DisplayModelTypes.Idle worldIdle(long display, Cancellation token) {
                        return world;
                    }
                });
        var tile = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(tile.drawn() == 2 && tile.restPosed() == 0 && tile.billboardsLeftOut() == 2,
                "Both placements stand with their billboard triangles left out: " + tile.billboardsLeftOut());
        String text = WorldStreamSourceController.creatureDetail(tile, 1);
        require(text.contains("(Stand pose, facing north, first texture per surface), 2 with camera-facing parts left out"), text);
        require(!WorldStreamSourceController.creatureDetail(
                standingCreatures(() -> new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.AVAILABLE, null,
                        lift(standing()), "Stand")).append(WorldStreamTileWriter.Exterior.builder(), 0, TILE,
                        Cancellation.NONE), 1).contains("camera-facing"), "Nothing to disclose without billboards");
    }

    static com.vibeminer.rendering.models.PreviewAnimation lift(MeshAppearance appearance) {
        var up = new com.vibeminer.rendering.models.PreviewAnimation.Track(
                com.vibeminer.rendering.models.PreviewAnimation.Interpolation.LINEAR, -1, 3, new int[] {0},
                new float[] {0, 0, 5});
        var bone = new com.vibeminer.rendering.models.PreviewAnimation.Bone(-1, 0, 0, 0, up, null, null, false);
        int vertices = appearance.mesh().vertexCount();
        int[] bones = new int[vertices * 4], weights = new int[vertices * 4];
        for (int v = 0; v < vertices; v++) weights[v * 4] = 255;
        return new com.vibeminer.rendering.models.PreviewAnimation("Stand", 1000, new long[0], List.of(bone),
                appearance.mesh(), new float[0], bones, weights);
    }

    static WorldTileCreatures standingCreatures(java.util.function.Supplier<DisplayModelTypes.Idle> idle) {
        var appearance = standing();
        return new WorldTileCreatures(token -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(1, 2, -110, -100, 10.0))), () -> "r1",
                (npc, token) -> qualified(7, null), new WorldTileCreatures.Previews() {
                    public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                        return preview(display, 0L, 0L, appearance);
                    }

                    public DisplayModelTypes.Idle idle(long display, Cancellation token) {
                        return idle.get();
                    }

                    private DisplayModelTypes.Preview preview(long display, Long extended, Long geosets,
                            MeshAppearance appearance) {
                        return WorldTileCreaturesTest.preview(display, extended, geosets, appearance);
                    }
                });
    }

    private static void creaturesStandInTheirIdlePoseOrSayWhyNot() throws Exception {
        var appearance = standing();
        var animation = lift(appearance);
        var available = new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.AVAILABLE, null, animation, "Stand");
        // The Stand sequence's first frame is baked into the converted mesh.
        var preview = preview(7, 0L, 0L, appearance);
        var stood = new WorldTileCreatures(token -> index(List.of()), () -> "r1", (npc, token) -> qualified(7, null),
                new WorldTileCreatures.Previews() {
                    public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                        return preview;
                    }

                    public DisplayModelTypes.Idle idle(long display, Cancellation token) {
                        return available;
                    }
                }).stand(7, preview, Cancellation.NONE);
        require(stood.model() != null && stood.model().restPose() == null, "A qualified Stand pose is baked");
        float[] v = stood.model().vertices();
        var expected = animation.pose(appearance.mesh(), 0).mesh().positions();
        require(v[0] == expected[0] && v[1] == expected[1] && v[2] == expected[2] && v[2] == 5,
                "Posed vertices match the animation's Stand frame: " + v[2]);
        // Placements of a posed model are not disclosed as rest-posed.
        var tile = standingCreatures(() -> available).append(WorldStreamTileWriter.Exterior.builder(), 0, TILE,
                Cancellation.NONE);
        require(tile.drawn() == 2 && tile.restPosed() == 0, "Both placements stand");
        require(WorldStreamSourceController.creatureDetail(tile, 1).contains("(Stand pose, facing north, first texture per surface)"),
                WorldStreamSourceController.creatureDetail(tile, 1));
        // No qualified sequence: the rest pose is drawn and the reason recorded.
        var missing = standingCreatures(() -> DisplayModelTypes.Idle.unavailable("The Stand sequence records no bone keys"));
        var rest = missing.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(rest.drawn() == 2 && rest.restPosed() == 2, "Rest-posed placements are counted");
        require(WorldStreamSourceController.creatureDetail(rest, 1).contains("(rest pose, facing north, first texture per surface)"),
                WorldStreamSourceController.creatureDetail(rest, 1));
        var otherSurface = new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.AVAILABLE, null, lift(new MeshAppearance(
                new PreviewMesh(new float[] {0, 0, 1, 1, 0, 1, 0, 1, 1}, new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3))),
                new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})))),
                "Stand");
        var mismatched = new WorldTileCreatures(token -> index(List.of()), () -> "r1",
                (npc, token) -> qualified(7, null), new WorldTileCreatures.Previews() {
                    public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                        return preview;
                    }

                    public DisplayModelTypes.Idle idle(long display, Cancellation token) {
                        return otherSurface;
                    }
                }).stand(7, preview, Cancellation.NONE);
        require(mismatched.model().restPose().contains("another rest surface"),
                "A sequence for another surface is not applied: " + mismatched.model().restPose());
        require(mismatched.model().vertices()[2] == 0, "The rest pose is kept");
        // An idle read failure keeps the rest pose; a busy reader is not cached as anything.
        var failing = new WorldTileCreatures(token -> index(List.of()), () -> "r1",
                (npc, token) -> qualified(7, null), new WorldTileCreatures.Previews() {
                    public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                        return preview;
                    }

                    public DisplayModelTypes.Idle idle(long display, Cancellation token) {
                        throw new IllegalStateException("broken");
                    }
                });
        require(failing.stand(7, preview, Cancellation.NONE).model().restPose()
                .equals("Stand pose failed: IllegalStateException"), "An idle failure is stated");
        var busyIdle = standingCreatures(() -> {
            throw new DisplayModelTypes.ServiceUnavailable("Display previews busy", false, null);
        });
        var busy = busyIdle.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(busy.count(WorldTileCreatures.Count.PREVIEWS_BUSY) == 2 && busy.drawn() == 0,
                "A busy idle read is a busy preview, not a rest pose");
    }

    private static void unreadableStandSequencesAreAskedAgainButUnsupportedOnesAreKept() throws Exception {
        var appearance = standing();
        var available = new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.AVAILABLE, null, lift(appearance),
                "Stand");
        // UNAVAILABLE ("retry can reassess it"): this tile draws the rest pose, the next tile asks again.
        var asked = new AtomicInteger();
        var streaming = standingCreatures(() -> asked.incrementAndGet() == 1
                ? new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.UNAVAILABLE, null, null,
                        "Model source is incomplete")
                : available);
        var first = streaming.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(first.drawn() == 2 && first.restPosed() == 2 && asked.get() == 1,
                "An unreadable Stand sequence draws the rest pose on this tile");
        var second = streaming.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(second.drawn() == 2 && second.restPosed() == 0 && asked.get() == 2,
                "It is not cached: the next tile reads it again and stands");
        // UNSUPPORTED is a lasting answer, cached with the model.
        var unsupportedAsked = new AtomicInteger();
        var unsupported = standingCreatures(() -> {
            unsupportedAsked.incrementAndGet();
            return new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.UNSUPPORTED, null, null,
                    "Bone 3 uses a cylindrical billboard");
        });
        unsupported.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        var again = unsupported.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(again.restPosed() == 2 && unsupportedAsked.get() == 1, "An unsupported sequence is cached");
        // A cancellation from a closed component is not a stated rest pose.
        var closed = standingCreatures(() -> {
            throw new CancellationException("Creature models closed");
        });
        var refused = closed.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(refused.drawn() == 0 && refused.count(WorldTileCreatures.Count.FAILED) == 2,
                "A closed component's cancellation propagates as a failed read");
    }
    private static void appendDrawsOnlyQualifiedSpawnsOfTheTile() {
        var previews = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(1, 2, -120, -100, 11.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0),
                WorldSpawnOverlayTest.spawn(1, 3, -600, -100, 13.0),
                WorldSpawnOverlayTest.spawn(1, 4, -100, -110, null))),
                () -> "r1",
                (npc, token) -> npc == 1 ? qualified(7, 1.5) : new WorldTileCreatures.Chosen(
                        new CreatureModelPolicy.Choice(CreatureModelPolicy.State.AMBIGUOUS,
                                CreatureModelPolicy.Reason.SEVERAL_DISPLAYS, 2, null, null, null, "2"), null),
                (display, token) -> {
                    previews.incrementAndGet();
                    return preview(display, 0L, 0L, appearance(true));
                });
        var builder = WorldStreamTileWriter.Exterior.builder();
        var summary = creatures.append(builder, 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 2 && summary.count(WorldTileCreatures.Count.NOT_QUALIFIED) == 1,
                "Two qualified points of the tile are drawn and the ambiguous one is counted");
        require(summary.omitted() == 1, "Neighbour-tile and height-less points are not counted for this tile");
        require(summary.models() == 1 && previews.get() == 1, "One display shares one model and one preview");
        require(builder.placementCount() == 2, "Both placements reach the objects builder");
        require(summary.placed().size() == 2
                        && summary.placed().stream().allMatch(c -> c.spawn().npcId() == 1 && c.npc() != null
                                && c.choice().displayId() == 7 && c.scale() == 1.5),
                "Each drawn placement records its spawn, NPC record, display choice and scale in file order");
        require(summary.plus(summary).placed().isEmpty(), "A summed outcome carries no per-tile records");
        var exterior = builder.build();
        require(exterior.placements() == 2 && exterior.draws() == 4, "Two ranges (opaque and cutout) per placement");
        var transform = WorldTileCreatures.transform(
                index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)))
                        .near(0, -100, -100, 1, 1).placed().getFirst(), qualified(7, 1.5));
        require(transform[0] == -100 && transform[1] == -100 && transform[2] == 10 && transform[3] == 1.5
                && transform[4] == -1 && transform[5] == 0, "Recorded point, client scale and identity heading");
        require(WorldTileCreatures.transform(
                index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)))
                        .near(0, -100, -100, 1, 1).placed().getFirst(), qualified(7, null))[3] == 1,
                "An unrecorded scale draws at 1");
        var empty = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0,
                new WorldFormats.Tile(10, 10, 1, 0, 0), Cancellation.NONE);
        require(empty.drawn() == 0 && empty.omitted() == 0 && empty.note() == null, "A tile without spawns is empty");
    }

    /** The tile's own NPCs not chosen yet go to {@code forNpcs} at once; the rest stay cached. */
    private static void aTileHandsItsUnchosenNpcsToTheChooserTogether() {
        var asked = new ArrayList<List<Long>>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        WorldTileCreatures.Chooser chooser = new WorldTileCreatures.Chooser() {
            @Override
            public WorldTileCreatures.Chosen choose(long npcId, Cancellation token) {
                return qualified(7, null);
            }

            @Override
            public WorldTileCreatures.Chooser forNpcs(java.util.Collection<Long> npcIds, Cancellation token)
                    throws Exception {
                asked.add(List.copyOf(npcIds));
                if (fail.get()) throw new java.io.IOException("outside source unreadable");
                return this;
            }
        };
        var rows = List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 2, -120, -100, 11.0),
                WorldSpawnOverlayTest.spawn(1, 3, -110, -110, 12.0),
                WorldSpawnOverlayTest.spawn(9, 4, -600, -100, 13.0));
        var revision = new AtomicReference<Object>("r1");
        var creatures = new WorldTileCreatures(token -> index(rows), revision::get, chooser,
                (display, token) -> preview(display, 0L, 0L, appearance(true)));
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(asked.equals(List.of(List.of(1L, 2L))), "One call for the tile's distinct NPCs: " + asked);
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(asked.size() == 1, "Chosen NPCs are not handed over again");
        // A failed batch leaves each NPC to be chosen by itself; the tile still draws.
        revision.set("r2");
        fail.set(true);
        var drawn = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(asked.size() == 2 && drawn.drawn() == 3, "A failed batch still draws: " + drawn.drawn());
    }

    private static void buildingsKeepTheirBudgetFirst() {
        var rows = new ArrayList<Map<String, Object>>();
        int spawns = WorldTileCreatures.TILE_PLACEMENTS + 8;
        for (int i = 0; i < spawns; i++) rows.add(WorldSpawnOverlayTest.spawn(1, i + 1, -100 - i, -100, 10.0));
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> preview(display, 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == WorldTileCreatures.TILE_PLACEMENTS
                && summary.count(WorldTileCreatures.Count.CAPPED) == spawns - WorldTileCreatures.TILE_PLACEMENTS,
                "Creature placements stop at the per-tile creature cap");
        // A tile whose buildings and doodads leave room for four placements gets four creatures.
        var builder = WorldStreamTileWriter.Exterior.builder();
        int room = builder.placementRoom();
        builder.image(1, 1, ByteBuffer.allocate(4));
        builder.model();
        builder.mesh(FloatBuffer.wrap(new float[24]), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0});
        for (int p = 0; p < room - 4; p++) builder.placement(0, 0, 0, 0, 1, 1, 0);
        var shared = creatures.append(builder, 0, TILE, Cancellation.NONE);
        require(shared.drawn() == 4 && shared.count(WorldTileCreatures.Count.CAPPED) == spawns - 4,
                "Buildings keep their placements; creatures fill only what remains");
        require(builder.build().placements() == room, "The shared file stays within its profile");
    }

    private static void cachesPerRevisionAndRetriesFailures() {
        var revision = new AtomicReference<Object>("r1");
        var loads = new AtomicInteger();
        var chooses = new AtomicInteger();
        var previews = new AtomicInteger();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var creatures = new WorldTileCreatures(token -> {
            loads.incrementAndGet();
            return index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)));
        }, revision::get, (npc, token) -> {
            chooses.incrementAndGet();
            return qualified(7, null);
        }, (display, token) -> {
            previews.incrementAndGet();
            if (fail.getAndSet(false)) throw new IllegalStateException("Display previews busy");
            return preview(display, 0L, 0L, appearance(true));
        });
        var failed = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(failed.count(WorldTileCreatures.Count.FAILED) == 1, "A failed preview is counted");
        var drawn = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(drawn.drawn() == 1 && previews.get() == 2, "A failed preview is retried, not cached");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 1 && chooses.get() == 1 && previews.get() == 2,
                "Index, choice and model are cached within one revision");
        revision.set("r2");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 2 && chooses.get() == 2 && previews.get() == 3,
                "A new revision reloads the index and drops cached choices and models");
        creatures.close();
        var closed = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(closed.drawn() == 0 && closed.note().contains("closed"),
                "A closed source is a creature note under a live tile token, never a cancellation");
    }

    private static void cancellationPropagatesAndSourceFailuresAreNotes() {
        var token = new Cancellation();
        var creatures = new WorldTileCreatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))),
                () -> "r1", (npc, t) -> {
                    token.cancel();
                    return qualified(7, null);
                }, (display, t) -> preview(display, 0L, 0L, appearance(true)));
        try {
            creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, token);
            require(false, "Cancellation propagates");
        } catch (CancellationException expected) {
            // Canceled.
        }
        var broken = new WorldTileCreatures(t -> {
            throw new IllegalStateException("no snapshot");
        }, () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> null);
        var summary = broken.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 0 && summary.note().contains("spawn source failed"),
                "A failed spawn source is a note, never a tile failure");
        var none = new WorldTileCreatures(t -> WorldSpawns.unavailable(
                com.vibeminer.concepts.locations.LocationTypes.State.UNKNOWN, "Polestar disabled"),
                () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> null);
        require(none.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE).note()
                .equals("no prepared spawn source"), "No source is disclosed");
    }

    private static void statusDisclosure() {
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0))), () -> "r1",
                (npc, token) -> npc == 1 ? qualified(7, null) : qualified(8, null),
                (display, token) -> preview(display, display == 8 ? 4L : 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        String text = WorldStreamSourceController.creatureDetail(summary, 1);
        require(text.equals("; 1 static creature model on 1 tile (rest pose, facing north, first texture per surface)"
                + "; spawns not drawn: 1 character appearance unavailable"), "Status line: " + text);
        require(WorldStreamSourceController.creatureDetail(WorldTileCreatures.Summary.NONE, 0).isEmpty(),
                "Nothing to disclose without creatures");
        require(WorldStreamSourceController.creatureDetail(WorldTileCreatures.Summary.failed("no prepared spawn source"), 0)
                .equals("; creatures: no prepared spawn source"), "A note is shown when nothing was considered");
    }

    /**
     * Humanoid NPC displays (#385) draw their race appearance whole, with item models and item
     * records left out counted; a refused appearance is cached, one that cannot be read yet is
     * asked once per tile and again on the next.
     */
    private static void humanoidNpcsDrawWholeOrSayWhy() {
        var asked = new java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger>();
        WorldTileCreatures.Npcs npcs = (extended, token) -> {
            asked.computeIfAbsent(extended, key -> new AtomicInteger()).incrementAndGet();
            if (extended == 70) {
                var result = new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY, appearance(true), Set.of(0), List.of(),
                        List.of(), false, null);
                return new com.vibeminer.concepts.races.RaceNpcs.Appearance(result, 1, 2, null, false);
            }
            if (extended == 100) {
                // Every visible section drawable: nothing left out.
                var clean = new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY, geosets(), Set.of(0), List.of(),
                        List.of(), false, null);
                return new com.vibeminer.concepts.races.RaceNpcs.Appearance(clean, 0, 0, null, false);
            }
            if (extended == 80)
                return com.vibeminer.concepts.races.RaceNpcs.Appearance.refused("there is no pre-baked NPC texture");
            return new com.vibeminer.concepts.races.RaceNpcs.Appearance(null, 0, 0,
                    "Some NPC appearance rows cannot be read yet.", true);
        };
        var previews = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0),
                WorldSpawnOverlayTest.spawn(3, 1, -120, -120, 13.0),
                WorldSpawnOverlayTest.spawn(3, 2, -130, -130, 14.0),
                WorldSpawnOverlayTest.spawn(4, 4, -140, -140, 15.0))), () -> "r1",
                (npc, token) -> qualified((int) (6 + npc), null),
                (display, token) -> {
                    previews.incrementAndGet();
                    return preview(display, display * 10, 0L, appearance(true));
                }, npcs);
        var builder = WorldStreamTileWriter.Exterior.builder();
        var summary = creatures.append(builder, 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 2 && builder.placementCount() == 2, "The readable NPCs are drawn: " + summary.drawn());
        require(summary.count(WorldTileCreatures.Count.CHARACTER_APPEARANCE) == 1,
                "A refused NPC appearance is a stated refusal");
        require(summary.count(WorldTileCreatures.Count.CHARACTER_RETRY) == 2,
                "Both spawns of the NPC that cannot be read yet are counted as retries");
        require(asked.get(90L).get() == 1, "An NPC that cannot be read yet is asked once per tile");
        // NPC 70's appearance has two visible sections it cannot draw (one without a surface, one
        // additive): it is drawn, and the sections left out are disclosed. NPC 100 draws whole.
        require(summary.npcs().equals(new WorldTileCreatures.NpcCounts(2, 1, 2, 1, 2)),
                "The drawn NPCs' left-out item records, item models and surfaces are counted: " + summary.npcs());
        require(summary.restPosed() == 2, "NPC appearances are static, disclosed as rest pose");
        String text = WorldStreamSourceController.creatureDetail(summary, 1);
        require(text.equals("; 2 static creature models on 1 tile (rest pose, facing north)"
                        + ", of which 2 humanoid NPCs (1 with 2 surfaces left out;"
                        + " 2 item models and 1 item record not drawn)"
                        + "; spawns not drawn: 1 character appearance unavailable, 2 character appearance not readable yet"),
                "Status line: " + text);
        var again = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(again.drawn() == 2 && asked.get(70L).get() == 1 && asked.get(80L).get() == 1,
                "Drawn and refused NPC appearances are cached");
        require(asked.get(90L).get() == 2 && again.count(WorldTileCreatures.Count.CHARACTER_RETRY) == 2,
                "An NPC that could not be read is asked again on the next tile");
        require(summary.plus(again).npcs().equals(new WorldTileCreatures.NpcCounts(4, 2, 4, 2, 4)),
                "NPC counts sum across tiles");
        // Without an NPC source every humanoid display is a stated refusal, never a guess.
        var none = new WorldTileCreatures(token -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))),
                () -> "r1", (npc, token) -> qualified(7, null),
                (display, token) -> preview(display, 70L, 0L, appearance(true)));
        var refused = none.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(refused.drawn() == 0 && refused.count(WorldTileCreatures.Count.CHARACTER_APPEARANCE) == 1,
                "No NPC source: a stated refusal");
    }

    /**
     * Distinct models stop at the tile's texture budget before the model ceiling; the spawns past
     * it are counted as capped.
     */
    private static void distinctModelsStopAtTheTileTextureBudget() {
        var rows = new ArrayList<Map<String, Object>>();
        int displays = 40;
        for (int i = 0; i < displays; i++) rows.add(WorldSpawnOverlayTest.spawn(i + 1, 1, -100 - i, -100, 10.0));
        var big = textured(256);
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified((int) (100 + npc), null), (display, token) -> preview(display, 0L, 0L, big));
        var builder = WorldStreamTileWriter.Exterior.builder();
        var summary = creatures.append(builder, 0, TILE, Cancellation.NONE);
        long perModel = WorldTileCreatures.surfaces(big, Set.of(0), new int[0], false, Cancellation.NONE)
                .model().pixels() * 4;
        long fit = WorldTileCreatures.TILE_TEXTURE_BYTES / perModel;
        require(fit < displays && fit < WorldTileCreatures.TILE_MODELS, "The fixture outgrows the byte budget only");
        require(summary.models() == fit, "Distinct models stop at the texture budget: " + summary.models());
        require(summary.drawn() == fit && summary.count(WorldTileCreatures.Count.CAPPED) == displays - fit,
                "New models past the budget are capped: " + summary.drawn());
    }

    private static void theModelCacheIsBoundedByBytes() {
        var model = WorldTileCreatures.surfaces(textured(256), Set.of(0), new int[0], false, Cancellation.NONE);
        long each = WorldTileCreatures.bytes(model);
        require(each > model.model().pixels() * 4, "A model's bytes count its texture chains and mesh");
        var refusal = WorldTileCreatures.Converted.refused(WorldTileCreatures.Count.MODEL_UNAVAILABLE, "no");
        require(WorldTileCreatures.bytes(refusal) < 1024, "A remembered refusal costs next to nothing");
        var cache = new WorldTileCreatures.ModelCache(each * 3);
        for (long key = 1; key <= 3; key++) cache.put(key, model);
        require(cache.totalBytes() == each * 3, "Every put is counted");
        cache.get(1L);
        cache.put(4L, model);
        require(cache.size() == 3 && cache.contains(1L) && !cache.contains(2L) && cache.totalBytes() == each * 3,
                "Past its byte bound the least recently used model is dropped");
        cache.put(4L, refusal);
        require(cache.totalBytes() == each * 2 + WorldTileCreatures.bytes(refusal),
                "Replacing an entry counts the difference");
        cache.remove(3L);
        cache.remove(3L);
        require(cache.size() == 2 && cache.totalBytes() == each + WorldTileCreatures.bytes(refusal),
                "A removed model gives its bytes back once");
        for (long key = 10; key < 1000; key++) cache.put(key, refusal);
        require(cache.contains(1L), "Refusals do not push models out by count");
        var tiny = new WorldTileCreatures.ModelCache(1);
        tiny.put(1L, model);
        require(tiny.size() == 1, "One model larger than the bound is still remembered");
        tiny.put(2L, model);
        require(tiny.size() == 1 && tiny.contains(2L) && tiny.totalBytes() == each, "The newest one is kept");
        tiny.clear();
        require(tiny.size() == 0 && tiny.totalBytes() == 0, "Clearing resets the byte count");
    }

    /**
     * The greedy walk skips a model that no longer fits the tile's texture budget and still adds a
     * later, smaller one; the allowance from the area share bounds the models' bytes the same way.
     */
    private static void tooLargeModelsAreSkippedAndSmallerOnesStillFit() {
        var big = textured(256);
        var small = textured(1);
        long perBig = WorldTileCreatures.surfaces(big, Set.of(0), new int[0], false, Cancellation.NONE)
                .model().pixels() * 4;
        long perSmall = WorldTileCreatures.surfaces(small, Set.of(0), new int[0], false, Cancellation.NONE)
                .model().pixels() * 4;
        long fit = WorldTileCreatures.TILE_TEXTURE_BYTES / perBig;
        int bigs = (int) fit + 2, smalls = 3;
        require(WorldTileCreatures.TILE_TEXTURE_BYTES - fit * perBig >= smalls * perSmall,
                "The fixture leaves room for the small models");
        // Spawns are taken nearest the tile centre first: the big displays, then the small ones.
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < bigs + smalls; i++) rows.add(WorldSpawnOverlayTest.spawn(i + 1, 1, -100 - i, -100, 10.0));
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified((int) (100 + npc), null),
                (display, token) -> preview(display, 0L, 0L, display - 100 <= bigs ? big : small));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.models() == fit + smalls && summary.count(WorldTileCreatures.Count.CAPPED) == bigs - fit,
                "Big models past the budget are skipped, the later small ones are added: " + summary.models());
        // The same walk under an allowance of three big models' bytes.
        long gpu = WorldTileCreatures.gpuBytes(WorldTileCreatures.surfaces(big, Set.of(0), new int[0], false,
                Cancellation.NONE).model());
        var shared = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified((int) (100 + npc), null),
                (display, token) -> preview(display, 0L, 0L, display - 100 <= bigs ? big : small));
        var allowed = shared.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, gpu * 3 + 4096, Cancellation.NONE);
        require(allowed.models() == 3 + smalls && allowed.bytes() <= gpu * 3 + 4096 && allowed.bytes() >= gpu * 3,
                "Models stop at the allowance and its bytes are reported: " + allowed.models() + " " + allowed.bytes());
        var none = shared.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, 0, Cancellation.NONE);
        require(none.drawn() == 0 && none.bytes() == 0
                        && none.count(WorldTileCreatures.Count.CAPPED) == bigs + smalls,
                "Without an allowance no creature is added");
    }

    /** Only humanoid NPC appearances are reduced to the NPC side; other creatures keep theirs. */
    private static void onlyNpcConversionsUseTheNpcSide() {
        var big = textured(512);
        WorldTileCreatures.Npcs npcs = (extended, token) -> new com.vibeminer.concepts.races.RaceNpcs.Appearance(
                new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY, big, Set.of(0), List.of(), List.of(),
                        false, null), 0, 0, null, false);
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0))), () -> "r1",
                (npc, token) -> qualified((int) (6 + npc), null),
                // Display 7 is a creature, display 8 a humanoid NPC (extended display 80).
                (display, token) -> preview(display, display == 8 ? 80L : 0L, 0L, big), npcs);
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 2, "Both are drawn: " + summary.drawn());
        var creature = creatures.cached(7).model();
        var npc = creatures.cached(8).model();
        require(creature.npc() == null && creature.images().stream().allMatch(i -> i.width() == WorldTileCreatures.IMAGE_SIDE),
                "A creature keeps the World image side");
        require(npc.npc() != null && npc.images().stream().allMatch(i -> i.width() == WorldTileCreatures.NPC_IMAGE_SIDE),
                "A humanoid NPC is reduced to the NPC side");
    }

    private static void npcImagesUseTheSmallerSide() {
        require(WorldTileCreatures.NPC_IMAGE_SIDE < WorldTileCreatures.IMAGE_SIDE, "NPC images are smaller");
        var texture = new MeshAppearance.Texture(512, 256, new int[512 * 256]);
        var npc = WorldTileCreatures.reduce(texture, WorldTileCreatures.OPAQUE, WorldTileCreatures.NPC_IMAGE_SIDE,
                Cancellation.NONE);
        require(npc.width() == 128 && npc.height() == 128, "Each side is capped at the NPC side");
        var model = WorldTileCreatures.surfaces(textured(512), Set.of(0), new int[0], false,
                WorldTileCreatures.NPC_IMAGE_SIDE, Cancellation.NONE).model();
        require(model.images().stream().allMatch(i -> i.width() == 128 && i.height() == 128),
                "Conversion reduces every image to the side it is given");
        for (int side : new int[] {0, 3, WorldTileCreatures.IMAGE_SIDE * 2}) {
            try {
                WorldTileCreatures.reduce(texture, WorldTileCreatures.OPAQUE, side, Cancellation.NONE);
                throw new AssertionError("Side " + side + " is refused");
            } catch (IllegalArgumentException expected) {
                // A side that is not a power of two up to IMAGE_SIDE is a programming error.
            }
        }
    }

    /** A source that knows a display's references draws its humanoid NPCs without their previews. */
    private static void humanoidDisplaysSkipTheirPreview() {
        var asked = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        WorldTileCreatures.Npcs npcs = (extended, token) -> new com.vibeminer.concepts.races.RaceNpcs.Appearance(
                new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY, appearance(true), Set.of(0), List.of(),
                        List.of(), false, null), 0, 0, null, false);
        var previews = new WorldTileCreatures.Previews() {
            @Override
            public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                asked.add(display);
                return WorldTileCreaturesTest.preview(display, display == 8 ? 80L : 0L, 0L, appearance(true));
            }

            @Override
            public DisplayModelTypes.Reference reference(long display, Cancellation token) {
                return WorldTileCreaturesTest.preview(display, display == 8 ? 80L : 0L, 0L, appearance(true))
                        .reference();
            }
        };
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0))), () -> "r1",
                (npc, token) -> qualified((int) (6 + npc), null), previews, npcs);
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 2 && summary.npcs().drawn() == 1, "The creature and the NPC are drawn");
        require(List.copyOf(asked).equals(List.of(7L)), "Only the creature's preview is read: " + asked);
    }

    /**
     * A humanoid NPC display whose own model file is missing (its preview a stated refusal) is
     * drawn from its race appearance: the display's references are enough.
     */
    private static void aHumanoidDisplayWithoutItsModelFileIsDrawnAsAnNpc() {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        WorldTileCreatures.Npcs npcs = (extended, token) -> new com.vibeminer.concepts.races.RaceNpcs.Appearance(
                new com.vibeminer.concepts.races.RaceAppearanceTypes.Result(
                        com.vibeminer.concepts.races.RaceTypes.State.READY, appearance(true), Set.of(0), List.of(),
                        List.of(), false, null), 0, 0, null, false);
        var reference = WorldTileCreaturesTest.preview(8, 80L, 0L, appearance(true)).reference();
        var previews = new WorldTileCreatures.Previews() {
            @Override
            public DisplayModelTypes.Preview preview(long display, Cancellation token) {
                asked.incrementAndGet();
                return new DisplayModelTypes.Preview(DisplayModelTypes.State.UNAVAILABLE, reference, null, Set.of(),
                        List.of(), false, "Model file is NOT_FOUND");
            }

            @Override
            public DisplayModelTypes.Reference reference(long display, Cancellation token) {
                return reference;
            }
        };
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0))), () -> "r1",
                (npc, token) -> qualified(8, null), previews, npcs);
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 1 && summary.npcs().drawn() == 1 && asked.get() == 0,
                "Drawn as an NPC without its preview: " + summary.drawn() + " " + asked.get());
    }

    private static void capsStopLookupsAndForeignCancellationIsAFailure() {
        var rows = new ArrayList<Map<String, Object>>();
        // More different NPCs than the cap, so each drawn point costs one uncached lookup.
        for (int i = 0; i < WorldTileCreatures.TILE_PLACEMENTS + 8; i++)
            rows.add(WorldSpawnOverlayTest.spawn(i + 1, 1, -100 - i, -100, 10.0));
        var chooses = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1", (npc, token) -> {
            chooses.incrementAndGet();
            return qualified(7, null);
        }, (display, token) -> preview(display, 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == WorldTileCreatures.TILE_PLACEMENTS, "The cap still draws its placements");
        require(chooses.get() == WorldTileCreatures.TILE_PLACEMENTS,
                "Spawns past the placement cap are capped without a lookup: " + chooses.get());
        var foreign = new WorldTileCreatures(token -> index(List.of(WorldSpawnOverlayTest.spawn(2, 1, -100, -100, 10.0))),
                () -> "r1", (npc, token) -> {
                    throw new CancellationException("component retired");
                }, (display, token) -> {
                    throw new CancellationException("preview future canceled");
                });
        var failed = foreign.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(failed.count(WorldTileCreatures.Count.FAILED) == 1,
                "A component's own cancellation under a live tile token is a failed lookup");
        var previewCanceled = new WorldTileCreatures(
                token -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> {
                    throw new CancellationException("preview future canceled");
                });
        require(previewCanceled.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE)
                .count(WorldTileCreatures.Count.FAILED) == 1, "A canceled preview future is a failed model");
    }

    private static void failedIndexLoadsBackOff() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var loads = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> {
            loads.incrementAndGet();
            throw new IllegalStateException("broken snapshot");
        }, () -> "r1", (npc, token) -> qualified(7, null), (display, token) -> null, now::get);
        require(creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE).note()
                .contains("spawn source failed"), "A failed load is a note");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 1, "A failed load is reused within its back-off, not retried per tile");
        now.addAndGet(WorldTileCreatures.INDEX_RETRY_NANOS);
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 2, "The load is retried after the back-off");
    }

    private static void conversionChecksVertexValuesAndLightsMissingNormals() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        var bound = List.of(new MeshAppearance.Surface(0, 0, true, true, false));
        var texture = List.of(new MeshAppearance.Texture(1, 1, new int[] {-1}));
        var lit = WorldTileCreatures.convert(preview(7, 0L, 0L, new MeshAppearance(mesh, new float[6], bound, texture)),
                Cancellation.NONE);
        float[] v = lit.model().vertices();
        require(v[3] == 0 && v[4] == 0 && v[5] == 1, "Missing normals light the model as facing up");
        // Preview meshes already bound their values; convert repeats the objects file's vertex
        // check per model, and the bridge test below covers a bad vertex reaching the file check.
    }

    /** Buildings survive every kind of creature problem on a tile that has buildings. */
    static final class BridgeAssembly {
        static void run() {
            java.util.function.Consumer<WorldStreamTileWriter.Exterior.Builder> buildings = builder -> {
                builder.image(1, 1, ByteBuffer.allocate(4));
                builder.model();
                builder.mesh(FloatBuffer.wrap(new float[24]), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0});
                builder.placement(0, 0, 0, 0, 1, 1, 0);
                builder.placement(0, 5, 0, 0, 1, 1, 0);
            };
            var good = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) ->
                    creatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))))
                            .append(builder, 0, tile, token), TILE, Cancellation.NONE);
            require(good.exterior().placements() == 3 && good.placements() == 2 && good.creatures().drawn() == 1,
                    "Buildings and creatures share one copy; only buildings count as buildings");
            // A bad vertex added past convert's checks fails the file check at build: buildings stay.
            var invalid = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.image(1, 1, ByteBuffer.allocate(4));
                builder.model();
                float[] bad = new float[24];
                bad[0] = Float.NaN;
                builder.mesh(FloatBuffer.wrap(bad), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 1});
                builder.placement(1, 0, 0, 0, 1, -1, 0);
                return WorldTileCreatures.Summary.NONE;
            }, TILE, Cancellation.NONE);
            requireBuildings(invalid, "invalid", "A creature set failing the file checks keeps the buildings");
            var closedSource = creatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))));
            closedSource.close();
            var closed = WorldStreamSourceBridge.assemble(buildings, false, "b",
                    (builder, tile, token) -> closedSource.append(builder, 0, tile, token), TILE, Cancellation.NONE);
            requireBuildings(closed, "closed", "A closed creature source keeps the buildings");
            var canceledPreview = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) ->
                    new WorldTileCreatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))),
                            () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> {
                                var future = new java.util.concurrent.CompletableFuture<DisplayModelTypes.Preview>();
                                future.cancel(false);
                                return future.join();
                            }).append(builder, 0, tile, token), TILE, Cancellation.NONE);
            require(canceledPreview.exterior().placements() == 2 && !canceledPreview.failed()
                            && canceledPreview.creatures().count(WorldTileCreatures.Count.FAILED) == 1,
                    "A canceled preview future keeps the buildings");
            var foreign = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.model();
                throw new CancellationException("component retired");
            }, TILE, Cancellation.NONE);
            requireBuildings(foreign, "canceled outside", "A cancellation that is not the tile's keeps the buildings");
            var thrown = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.model();
                throw new IllegalStateException("authored");
            }, TILE, Cancellation.NONE);
            requireBuildings(thrown, "failed", "A thrown creature failure keeps the buildings");
            var own = new Cancellation();
            try {
                WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                    own.cancel();
                    throw new CancellationException("tile canceled");
                }, TILE, own);
                require(false, "The tile's own cancellation propagates");
            } catch (CancellationException expected) {
                // Preempted by terrain.
            }
            var none = WorldStreamSourceBridge.assemble(null, true, "refused", (builder, tile, token) -> {
                throw new IllegalStateException("authored");
            }, TILE, Cancellation.NONE);
            require(none.exterior() == null && none.failed() && none.note().equals("refused"),
                    "Without buildings a creature failure delivers nothing and keeps the building note");
        }

        private static WorldTileCreatures creatures(WorldSpawnOverlay.Loader loader) {
            return new WorldTileCreatures(loader, () -> "r1", (npc, token) -> qualified(7, null),
                    (display, token) -> preview(display, 0L, 0L, appearance(true)));
        }

        private static void requireBuildings(WorldStreamSourceBridge.ExteriorCopy copy, String note, String message) {
            require(copy.exterior() != null && copy.exterior().placements() == 2 && copy.placements() == 2
                    && !copy.failed() && copy.creatures().drawn() == 0
                    && copy.creatures().note() != null && copy.creatures().note().contains(note),
                    message + ": " + (copy.creatures().note()));
        }
    }

    static WorldSpawns.Index index(List<Map<String, Object>> rows) {
        return WorldSpawnOverlayTest.index(rows);
    }

    static WorldTileCreatures.Chosen qualified(int display, Double scale) {
        return new WorldTileCreatures.Chosen(new CreatureModelPolicy.Choice(CreatureModelPolicy.State.QUALIFIED,
                CreatureModelPolicy.Reason.SINGLE_DISPLAY, 1, display, 20L, 123456L, "single"), scale);
    }

    static DisplayModelTypes.Preview preview(long display, Long extended, Long geosets, MeshAppearance appearance) {
        return preview(display, extended, geosets, Map.of(), appearance);
    }

    /** {@code displayGeosets}: the display's geoset group to value choices (null unknown). */
    static DisplayModelTypes.Preview preview(long display, Long extended, Long geosets,
            Map<Integer, Integer> displayGeosets,
            MeshAppearance appearance) {
        return new DisplayModelTypes.Preview(DisplayModelTypes.State.PARTIAL,
                new DisplayModelTypes.Reference(display, 20, 123456, List.of(), extended, geosets, displayGeosets),
                appearance, Set.of(0), List.of(), false, null);
    }

    /**
     * Four parts: part 0 one opaque triangle, part 1 alpha-keyed, part 2 unbound and part 3 a rigid
     * card on an unlit additive surface. With {@code opaque} false, part 0 is alpha-keyed too.
     */
    static MeshAppearance appearance(boolean opaque) {
        return appearance(opaque, new MeshAppearance.Texture(2, 2, new int[] {-1, -1, -1, -1}));
    }

    static MeshAppearance appearance(boolean opaque, MeshAppearance.Texture texture) {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0, 5, 5, 5, 6, 5, 5, 5, 6, 5, 7, 7, 7};
        int[] indices = {0, 1, 2, 0, 2, 1, 0, 1, 6, 3, 4, 5};
        var mesh = new PreviewMesh(positions, indices, List.of(new PreviewMesh.Part(0, 0, 3),
                new PreviewMesh.Part(0, 3, 3), new PreviewMesh.Part(0, 6, 3), new PreviewMesh.Part(0, 9, 3)));
        float[] uv = new float[7 * 2];
        float[] normals = new float[7 * 3];
        for (int v = 0; v < 7; v++) normals[v * 3 + 2] = 1;
        var cards = List.of(new MeshAppearance.RigidBillboard(3, new MeshAppearance.Vec3(5, 5, 5), new int[] {3, 4, 5}));
        var additive = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE,
                List.of(new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, true, true)),
                MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        return new MeshAppearance(mesh, uv, normals,
                List.of(new MeshAppearance.Surface(0, 0, true, true, !opaque),
                        new MeshAppearance.Surface(1, 0, true, true, true),
                        new MeshAppearance.Surface(3, additive)),
                List.of(texture), cards);
    }

    /** {@link #appearance(boolean)} (opaque) with one flat {@code side} x {@code side} texture. */
    static MeshAppearance textured(int side) {
        int[] argb = new int[side * side];
        java.util.Arrays.fill(argb, 0xff808080);
        return appearance(true, new MeshAppearance.Texture(side, side, argb));
    }

    /** Three one-triangle parts with geoset IDs 0, 100 and 101 (group 1 has x00 and x01), all opaque. */
    static MeshAppearance geosets() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2, 0, 2, 1, 1, 0, 2},
                List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(100, 3, 3),
                        new PreviewMesh.Part(101, 6, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, false),
                new MeshAppearance.Surface(1, 0, true, true, false), new MeshAppearance.Surface(2, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /** One opaque part on geoset 101 only: nothing in the default set. */
    static MeshAppearance hiddenGeosets() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(101, 0, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /** One part on an unlit additive surface. */
    static MeshAppearance additiveOnly() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        var additive = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE,
                List.of(new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, true, true)),
                MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, additive)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /** One part with no surface bound. */
    static MeshAppearance unboundOnly() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    /** One alpha-keyed part. */
    static MeshAppearance cutoutOnly() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, true)),
                List.of(new MeshAppearance.Texture(2, 2, new int[] {0, -1, -1, 0})));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
