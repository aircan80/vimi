package com.vibeminer.concepts.races;

import static com.vibeminer.concepts.races.RaceAppearancePlanTest.BUILD;
import static com.vibeminer.concepts.races.RaceAppearancePlanTest.replace;
import static com.vibeminer.concepts.races.RaceAppearancePlanTest.row;
import static com.vibeminer.concepts.races.RaceAppearancePlanTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A humanoid NPC's plan over the reviewed Orc fixture (#385). Authored tables only. */
final class RaceNpcAppearanceTest {
    /**
     * The Orc male fixture (model 3, layout 105, a 2048 x 1024 body canvas, class loadout item 100)
     * with three item displays: 600 plain, 601 a helm with an item model, 602 a chest that sets the
     * robe group (GeosetGroup[2] = 1); and one customization option 70 of model 3 with choice 71.
     */
    private static List<MemoryTables.FakeTable> fixture() {
        var seed = new ArrayList<>(RaceAppearancePlanTest.orcFixture(3, 105));
        replace(
                seed,
                table(
                        "ItemDisplayInfo",
                        "9F3AB8A9",
                        "GeosetGroupOverride,GeosetGroup:6,ModelResourcesID:2",
                        row(600, "0", "2", "0", "0", "0", "0", "0", "0", "0"),
                        row(601, "0", "0", "0", "0", "0", "0", "0", "55", "0"),
                        row(602, "0", "0", "0", "1", "0", "0", "0", "0", "0")));
        replace(
                seed,
                table(
                        "ChrCustomizationOption",
                        "DCC2A86E",
                        "ChrModelID,Requirement",
                        row(70, "3", "0")));
        replace(
                seed,
                table(
                        "ChrCustomizationChoice",
                        "9559C358",
                        "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID",
                        row(71, "70", "0", "0", "0"),
                        row(72, "70", "1", "0", "0")));
        return seed;
    }

    private interface Body<T> {
        T run(RacesService service, RaceMedia media, RaceAppearancePlan plans) throws Exception;
    }

    private static <T> T with(Body<T> body) throws Exception {
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", fixture())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var media = new RaceMedia(service, tables, null, null);
            return body.run(service, media, new RaceAppearancePlan(service, media, tables));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static final RaceMediaTypes.Variant ORC_MALE =
            new RaceMediaTypes.Variant(3, 0, 1, 1, 1);

    @Test
    void anNpcPlanUsesItsBakeAsTheWholeBodyAndItsSlotsInsteadOfTheLoadout() throws Exception {
        var npc =
                new RaceAppearancePlan.Npc(
                        List.of(new RaceNpcs.Item(0, 601), new RaceNpcs.Item(6, 600)), 4242);
        var plan =
                with(
                        (service, media, plans) ->
                                plans.build(
                                        2,
                                        1,
                                        ORC_MALE,
                                        Cancellation.NONE,
                                        new ArrayList<>(),
                                        null,
                                        npc));
        var body = plan.layers().stream().filter(layer -> layer.type() == 1).toList();
        assertEquals(1, body.size(), "the bake replaces every body layer");
        var bake = body.getFirst();
        assertEquals(4242, bake.fileDataId());
        // The 2048 x 1024 body canvas is drawn at most 256 on a side for an NPC.
        assertEquals(
                List.of(new RaceAppearancePlan.Canvas(1, 256, 128)),
                plan.canvases().stream().filter(canvas -> canvas.type() == 1).toList());
        assertEquals(
                List.of(0, 0, 256, 128), List.of(bake.x(), bake.y(), bake.width(), bake.height()));
        assertTrue(plan.npc());
        assertEquals(1, bake.blend());
        // Slot items, not the class loadout (item 100): slot 0 is the head (1), slot 6 the feet
        // (8).
        assertEquals(
                List.of(1, 8),
                plan.items().stream().map(RaceAppearancePlan.Item::inventoryType).toList());
        assertEquals(List.of(2, 0, 0, 0, 0, 0), plan.items().get(1).geosets());
        assertTrue(plan.items().stream().noneMatch(item -> item.itemId() == 100));
    }

    @Test
    void aRobeComesFromTheDisplaysOwnGeosetGroupNeverFromTheSlot() throws Exception {
        var robe = new RaceAppearancePlan.Npc(List.of(new RaceNpcs.Item(3, 602)), 4242);
        var chest = new RaceAppearancePlan.Npc(List.of(new RaceNpcs.Item(3, 600)), 4242);
        for (var npc : List.of(robe, chest)) {
            var plan =
                    with(
                            (service, media, plans) ->
                                    plans.build(
                                            2,
                                            1,
                                            ORC_MALE,
                                            Cancellation.NONE,
                                            new ArrayList<>(),
                                            null,
                                            npc));
            assertEquals(
                    npc == robe ? 20 : 5,
                    plan.items().getFirst().inventoryType(),
                    "slot 3 is a robe only when its display sets the robe group");
        }
    }

    @Test
    void itemModelsHeldItemsCapesAndTabardsAreCountedNotRefused() throws Exception {
        var npc =
                new RaceAppearancePlan.Npc(
                        List.of(
                                new RaceNpcs.Item(0, 601), // helm with an item model
                                new RaceNpcs.Item(6, 600), // boots without one
                                new RaceNpcs.Item(15, 650), // a held item past the slot table
                                new RaceNpcs.Item(9, 600), // a tabard (19)
                                new RaceNpcs.Item(10, 600)), // a cape (16)
                        4242);
        var omissions =
                with(
                        (service, media, plans) ->
                                plans.npcOmissions(npc, Cancellation.NONE, new ArrayList<>()));
        assertEquals(new RaceAppearancePlan.NpcOmissions(2, 0, 2), omissions);
        var plan =
                with(
                        (service, media, plans) ->
                                plans.build(
                                        2,
                                        1,
                                        ORC_MALE,
                                        Cancellation.NONE,
                                        new ArrayList<>(),
                                        null,
                                        npc));
        // Only the helm and the boots are clothing; the cape and tabard are left out.
        assertEquals(
                List.of(1, 8),
                plan.items().stream().map(RaceAppearancePlan.Item::inventoryType).toList());
    }

    @Test
    void anItemDisplayThatCannotBeReadNowIsUnreadableNotAnOmission() throws Exception {
        // The fixture's ItemDisplayInfo is PARTIAL, so display 999 is UNKNOWN rather than absent.
        var npc = new RaceAppearancePlan.Npc(List.of(new RaceNpcs.Item(3, 999)), 4242);
        assertThrows(
                RacesService.Unreadable.class,
                () ->
                        with(
                                (service, media, plans) ->
                                        plans.npcOmissions(
                                                npc, Cancellation.NONE, new ArrayList<>())));
        assertThrows(
                RacesService.Unreadable.class,
                () ->
                        with(
                                (service, media, plans) ->
                                        plans.build(
                                                2,
                                                1,
                                                ORC_MALE,
                                                Cancellation.NONE,
                                                new ArrayList<>(),
                                                null,
                                                npc)));
        // A display the build records as absent is a counted omission.
        var absent =
                withTables(
                        tables ->
                                answer(
                                        tables,
                                        "ItemDisplayInfo",
                                        TableTypes.RowOutcome.ABSENT,
                                        null),
                        (service, media, plans) ->
                                plans.npcOmissions(npc, Cancellation.NONE, new ArrayList<>()));
        assertEquals(new RaceAppearancePlan.NpcOmissions(0, 1, 0), absent);
    }

    @Test
    void aNightElfNpcNeedsNoSelectedBaseSkinBecauseItsBakeCoversTheBody() throws Exception {
        var tables =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                RaceAppearancePlanTest.nightElfFixture(7, 109, 0, "0", false))
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var plans =
                    new RaceAppearancePlan(
                            service, new RaceMedia(service, tables, null, null), tables);
            var variant = new RaceMediaTypes.Variant(7, 0, 1, 1, 1);
            assertThrows(
                    RacesService.Projection.class,
                    () -> plans.build(4, 1, variant, Cancellation.NONE, new ArrayList<>()));
            var plan =
                    plans.build(
                            4,
                            1,
                            variant,
                            Cancellation.NONE,
                            new ArrayList<>(),
                            null,
                            new RaceAppearancePlan.Npc(List.of(), 4242));
            assertTrue(plan.layers().stream().anyMatch(layer -> layer.fileDataId() == 4242));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void npcChoicesMustBelongToTheModelsOwnOptions() throws Exception {
        var chosen =
                with(
                        (service, media, plans) ->
                                new RaceCustomization(media)
                                        .npc(
                                                2,
                                                1,
                                                3,
                                                Map.of(70L, 72L),
                                                Cancellation.NONE,
                                                new ArrayList<>()));
        assertEquals(72L, chosen.byOption().get(70L), "the recorded choice replaces the sample");
        for (var unknown : List.of(Map.of(70L, 99L), Map.of(80L, 71L))) {
            var refused =
                    assertThrows(
                            RacesService.Projection.class,
                            () ->
                                    with(
                                            (service, media, plans) ->
                                                    new RaceCustomization(media)
                                                            .npc(
                                                                    2,
                                                                    1,
                                                                    3,
                                                                    unknown,
                                                                    Cancellation.NONE,
                                                                    new ArrayList<>())));
            assertTrue(refused.getMessage().contains("the customization choices are unknown"));
        }
    }

    /** {@code tables} with every row of {@code table} answering {@code outcome}. */
    private static com.vibeminer.formats.tables.BuildTables answer(
            com.vibeminer.formats.tables.BuildTables tables,
            String table,
            TableTypes.RowOutcome outcome,
            TableTypes.Page page) {
        return (com.vibeminer.formats.tables.BuildTables)
                java.lang.reflect.Proxy.newProxyInstance(
                        RaceNpcAppearanceTest.class.getClassLoader(),
                        new Class<?>[] {com.vibeminer.formats.tables.BuildTables.class},
                        (proxy, method, args) -> {
                            boolean named =
                                    args != null
                                            && args.length > 0
                                            && (TableTypes.Ref.forName(table).equals(args[0])
                                                    || args[0] instanceof TableTypes.PageRequest r
                                                            && TableTypes.Ref.forName(table)
                                                                    .equals(r.table()));
                            if (named && outcome != null && method.getName().equals("get")) {
                                return java.util.concurrent.CompletableFuture.completedFuture(
                                        new TableTypes.RowResult(null, outcome, null, null));
                            }
                            if (named && page != null && method.getName().equals("page")) {
                                return java.util.concurrent.CompletableFuture.completedFuture(page);
                            }
                            try {
                                return method.invoke(tables, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    private static <T> T withTables(
            java.util.function.UnaryOperator<com.vibeminer.formats.tables.BuildTables> wrap,
            Body<T> body)
            throws Exception {
        var raw =
                new MemoryTables()
                        .add(BUILD, "enUS", fixture())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var tables = wrap.apply(raw);
        var service = new RacesService(tables);
        try {
            var media = new RaceMedia(service, tables, null, null);
            return body.run(service, media, new RaceAppearancePlan(service, media, tables));
        } finally {
            service.closeAsync().join();
            raw.closeAsync().join();
        }
    }

    // --- Rendering an NPC plan: drawn whole, refused, or asked for again ---

    /** A build with one unrelated empty file (an empty MemoryFiles opens no build). */
    private static com.vibeminer.files.MemoryFiles placeholder() {
        return new com.vibeminer.files.MemoryFiles()
                .add(
                        BUILD,
                        "enUS",
                        new com.vibeminer.files.FileTypes.BuildFile(
                                1,
                                null,
                                com.vibeminer.files.FileTypes.FilePathSource.NONE,
                                0L,
                                "e".repeat(32),
                                com.vibeminer.files.FileTypes.FileFormat.UNKNOWN,
                                com.vibeminer.files.FileTypes.FileFormatSource.NONE,
                                com.vibeminer.files.FileTypes.FileStorage.STORED,
                                false),
                        new byte[0],
                        List.of());
    }

    private static RaceAppearanceTypes.Result renderNpc(com.vibeminer.files.MemoryFiles memory) {
        var build = BUILD;
        var files = memory.forBuild(build, "enUS", Cancellation.NONE).join().files();
        var tables =
                new MemoryTables()
                        .add(build, "enUS", List.of())
                        .forBuild(build, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var service = new RacesService(tables, files, generated);
        try {
            var mesh =
                    new com.vibeminer.rendering.models.PreviewMesh(
                            new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                            new int[] {0, 1, 2},
                            List.of(new com.vibeminer.rendering.models.PreviewMesh.Part(0, 0, 3)));
            var base =
                    new RaceMediaTypes.Preview(
                            RaceTypes.State.PARTIAL,
                            ORC_MALE,
                            mesh,
                            java.util.Set.of(0),
                            List.of(),
                            false,
                            null);
            var plan =
                    new RaceAppearancePlan.Plan(
                            List.of(new RaceAppearancePlan.Canvas(1, 4, 4)),
                            List.of(
                                    new RaceAppearancePlan.Layer(
                                            1, 100, 0, 0, 4, 4, 1, 0, "NPC bake #100")),
                            List.of(),
                            RaceAppearanceProfiles.Profile.LEGACY,
                            true);
            var prepared =
                    new RaceAppearance.Prepared(
                            base,
                            plan,
                            com.vibeminer.generated.GeneratedKey.hash(
                                    "npc-bake".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            List.of(),
                            null,
                            null,
                            null);
            return new RaceAppearance(
                            service,
                            new RaceMedia(service, tables, files, generated),
                            tables,
                            files,
                            generated)
                    .render(prepared, Cancellation.NONE);
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
            files.close();
        }
    }

    @Test
    void anNpcWhoseBakeIsMissingIsRefusedNotDrawnUntextured() {
        // File 100 is not in the build.
        var result = renderNpc(placeholder());
        assertEquals(null, result.appearance());
        assertEquals(RaceTypes.State.UNAVAILABLE, result.state());
        assertTrue(
                result.message().startsWith("NPC texture #100 is FILE_FAILED"), result.message());
    }

    @Test
    void anNpcWhoseBakeCannotBeReadNowIsAskedForAgain() {
        var file =
                new com.vibeminer.files.FileTypes.BuildFile(
                        100,
                        null,
                        com.vibeminer.files.FileTypes.FilePathSource.NONE,
                        512L,
                        "c".repeat(32),
                        com.vibeminer.files.FileTypes.FileFormat.UNKNOWN,
                        com.vibeminer.files.FileTypes.FileFormatSource.NONE,
                        com.vibeminer.files.FileTypes.FileStorage.STORED,
                        false);
        var memory =
                new com.vibeminer.files.MemoryFiles()
                        .add(BUILD, "enUS", file, new byte[512], List.of());
        memory.failure(
                BUILD,
                "enUS",
                100,
                com.vibeminer.files.FileTypes.FileRead.failed(
                        com.vibeminer.files.FileTypes.FileReadState.NEEDS_NETWORK,
                        0,
                        "FILES_NEEDS_NETWORK",
                        "not stored locally",
                        null));
        var unreadable = assertThrows(RacesService.Unreadable.class, () -> renderNpc(memory));
        assertTrue(unreadable.getMessage().contains("NPC texture #100 cannot be read yet"));
    }

    // --- End to end through RacesService.npcAppearance ---

    /**
     * The Orc fixture with its race model listed (ChrModel 3 -> display 20 -> model data 30 -> M2
     * 1234, not stored) and NPC 50: Orc male warrior, bake material 4900 (file 4242), choice 70 ->
     * 72 and boots (slot 6, display 600).
     */
    private static List<MemoryTables.FakeTable> npcFixture() {
        var seed = new ArrayList<>(fixture());
        replace(
                seed,
                RaceNpcsTest.table(
                        904,
                        "ChrModel",
                        List.of(
                                RaceNpcsTest.reference("DisplayID", "CreatureDisplayInfo"),
                                RaceNpcsTest.number("Sex"),
                                RaceNpcsTest.number("CharComponentTextureLayoutID")),
                        List.of(RaceNpcsTest.row(3, 20, 0, 105))));
        var textures =
                seed.stream()
                        .filter(table -> table.entry().name().equals("TextureFileData"))
                        .findFirst()
                        .orElseThrow();
        var textureRows = new ArrayList<>(textures.rows());
        textureRows.add(row(4242, "0", "4900"));
        replace(
                seed,
                table(
                        "TextureFileData",
                        "BD7C74C2",
                        "UsageType,MaterialResourcesID",
                        textureRows.toArray(com.vibeminer.formats.db2.Db2Types.Row[]::new)));
        seed.add(
                RaceNpcsTest.table(
                        905,
                        "ChrRaceXChrModel",
                        List.of(
                                RaceNpcsTest.reference("ChrRacesID", "ChrRaces"),
                                RaceNpcsTest.reference("ChrModelID", "ChrModel")),
                        List.of(RaceNpcsTest.row(1, 2, 3))));
        seed.add(
                RaceNpcsTest.table(
                        906,
                        "CreatureDisplayInfo",
                        List.of(RaceNpcsTest.reference("ModelID", "CreatureModelData")),
                        List.of(RaceNpcsTest.row(20, 30))));
        seed.add(
                RaceNpcsTest.table(
                        907,
                        "CreatureModelData",
                        List.of(RaceNpcsTest.number("FileDataID")),
                        List.of(RaceNpcsTest.row(30, 1234))));
        seed.add(
                RaceNpcsTest.table(
                        901,
                        "CreatureDisplayInfoExtra",
                        List.of(
                                RaceNpcsTest.reference("DisplayRaceID", "ChrRaces"),
                                RaceNpcsTest.number("DisplaySexID"),
                                RaceNpcsTest.number("DisplayClassID"),
                                RaceNpcsTest.number("BakeMaterialResourcesID")),
                        List.of(RaceNpcsTest.row(50, 2, 0, 1, 4900))));
        seed.add(
                RaceNpcsTest.table(
                        902,
                        "CreatureDisplayInfoOption",
                        List.of(
                                RaceNpcsTest.reference(
                                        "ChrCustomizationOptionID", "ChrCustomizationOption"),
                                RaceNpcsTest.reference(
                                        "ChrCustomizationChoiceID", "ChrCustomizationChoice"),
                                RaceNpcsTest.reference(
                                        "CreatureDisplayInfoExtraID", "CreatureDisplayInfoExtra")),
                        List.of(RaceNpcsTest.row(1, 70, 72, 50))));
        seed.add(
                RaceNpcsTest.table(
                        903,
                        "NPCModelItemSlotDisplayInfo",
                        List.of(
                                RaceNpcsTest.reference("ItemDisplayInfoID", "ItemDisplayInfo"),
                                RaceNpcsTest.number("ItemSlot"),
                                RaceNpcsTest.reference("NpcModelID", "CreatureDisplayInfoExtra")),
                        List.of(RaceNpcsTest.row(1, 600, 6, 50))));
        return seed;
    }

    private static RaceNpcs.Appearance npcAppearance(
            java.util.function.UnaryOperator<com.vibeminer.formats.tables.BuildTables> wrap) {
        var raw =
                new MemoryTables()
                        .add(BUILD, "enUS", npcFixture())
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var files = placeholder().forBuild(BUILD, "enUS", Cancellation.NONE).join().files();
        var service =
                new RacesService(
                        wrap.apply(raw), files, new com.vibeminer.generated.MemoryGeneratedStore());
        try {
            return service.npcAppearance(50, Cancellation.NONE).join();
        } finally {
            service.closeAsync().join();
            raw.closeAsync().join();
            files.close();
        }
    }

    @Test
    void theServiceRefusesWhatIsMissingAndAsksAgainForWhatCannotBeReadNow() {
        // Everything readable, but the race model file is not stored: a stated refusal.
        var refused = npcAppearance(tables -> tables);
        assertTrue(refused.refusal() != null && !refused.retry(), String.valueOf(refused));
        assertEquals("Model file is UNKNOWN_FILE", refused.refusal());
        // A customization table that cannot be read now, inside the appearance preparation.
        var page =
                new TableTypes.Page(
                        null,
                        TableTypes.PageState.NOT_READABLE,
                        List.of(),
                        null,
                        null,
                        0,
                        "TABLE_NOT_READABLE",
                        "not readable now");
        var cold = npcAppearance(tables -> answer(tables, "ChrCustomizationChoice", null, page));
        assertTrue(cold.retry(), String.valueOf(cold));
        assertTrue(cold.refusal().contains("ChrCustomizationChoice"), cold.refusal());
        // An item display row that cannot be read now: asked again, never drawn without it.
        var unknown =
                npcAppearance(
                        tables ->
                                answer(
                                        tables,
                                        "ItemDisplayInfo",
                                        TableTypes.RowOutcome.UNKNOWN,
                                        null));
        assertTrue(unknown.retry(), String.valueOf(unknown));
    }
}
