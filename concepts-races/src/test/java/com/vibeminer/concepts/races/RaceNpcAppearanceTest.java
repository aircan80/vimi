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
        assertEquals(
                List.of(0, 0, 2048, 1024),
                List.of(bake.x(), bake.y(), bake.width(), bake.height()));
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
    void itemModelsAndHeldItemsAreCountedAsAttachmentsNotRefused() throws Exception {
        var npc =
                new RaceAppearancePlan.Npc(
                        List.of(
                                new RaceNpcs.Item(0, 601), // helm with an item model
                                new RaceNpcs.Item(6, 600), // boots without one
                                new RaceNpcs.Item(15, 650), // a held item past the slot table
                                new RaceNpcs.Item(8, 999)), // a display that is not recorded
                        4242);
        var omissions =
                with(
                        (service, media, plans) ->
                                plans.npcOmissions(npc, Cancellation.NONE, new ArrayList<>()));
        assertEquals(2, omissions.attachments());
        assertEquals(1, omissions.unread());
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
}
