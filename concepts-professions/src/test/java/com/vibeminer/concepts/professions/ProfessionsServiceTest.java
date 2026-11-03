package com.vibeminer.concepts.professions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProfessionsServiceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, String type, String target, int array) {}

    private static Field n(String name) {
        return new Field(name, "integer", null, 1);
    }

    private static Field t(String name) {
        return new Field(name, "string", null, 1);
    }

    private static Field f(String name) {
        return new Field(name, "float", null, 1);
    }

    private static Field r(String name, String target) {
        return new Field(name, "integer", target, 1);
    }

    private static Field a(String name, int count, String target) {
        return new Field(name, "integer", target, count);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), null);
    }

    private static MemoryTables.FakeTable table(
            String name, String hash, List<Field> fields, List<Db2Types.Row> rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            for (int i = 0; i < field.array(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.array() == 1 ? field.name() : field.name() + "[" + i + "]",
                                field.type(),
                                false,
                                null,
                                null));
            }
            var kind =
                    switch (field.type()) {
                        case "string" -> TableSchema.ColumnKind.LOCSTRING;
                        case "float" -> TableSchema.ColumnKind.FLOAT;
                        default -> TableSchema.ColumnKind.INT;
                    };
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            kind,
                            kind == TableSchema.ColumnKind.LOCSTRING ? null : 32,
                            kind == TableSchema.ColumnKind.LOCSTRING ? null : true,
                            field.array(),
                            field.target() == null
                                    ? null
                                    : new TableSchema.ForeignKey(field.target(), "ID"),
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            true,
                            WowLocale.parse("enUS"),
                            null,
                            null));
        }
        var entry =
                new TableTypes.Entry(
                        Integer.toUnsignedLong(name.hashCode()),
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "fixture", hash),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of(hash),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static List<MemoryTables.FakeTable> core() {
        return new ArrayList<>(
                List.of(
                        table(
                                "SkillLine",
                                "6763217C",
                                List.of(
                                        t("DisplayName_lang"),
                                        t("Description_lang"),
                                        r("CategoryID", "SkillLineCategory"),
                                        n("SpellIconFileID"),
                                        n("ParentSkillLineID")),
                                List.of(
                                        row(171, "Alchemy", "Brew things", "11", "1000", "0"),
                                        row(185, "Cooking", "Cook things", "9", "1001", "0"),
                                        row(182, "Herbalism", "Gather things", "11", "1002", "0"),
                                        row(43, "Swords", "Weapon", "6", "1003", "0"))),
                        table(
                                "SkillLineCategory",
                                "67B4FE22",
                                List.of(t("Name_lang")),
                                List.of(row(9, "Secondary"), row(11, "Professions"))),
                        table(
                                "TradeSkillCategory",
                                "7F2B6CD0",
                                List.of(
                                        r("SkillLineID", "SkillLine"),
                                        n("ParentTradeSkillCategoryID"),
                                        t("Name_lang"),
                                        n("OrderIndex")),
                                List.of(row(40, "171", "0", "Potions", "1"))),
                        table(
                                "SkillLineAbility",
                                "224F7EA0",
                                List.of(
                                        r("SkillLine", "SkillLine"),
                                        r("Spell", "Spell"),
                                        n("MinSkillLineRank"),
                                        n("TradeSkillCategoryID"),
                                        n("TrivialSkillLineRankLow"),
                                        n("TrivialSkillLineRankHigh"),
                                        n("NumSkillUps"),
                                        n("AcquireMethod"),
                                        n("ClassMask"),
                                        a("RaceMasks", 2, null)),
                                List.of(
                                        row(
                                                11, "171", "100", "25", "40", "50", "100", "1", "0",
                                                "0", "1", "0"),
                                        row(
                                                12, "171", "101", "30", "40", "60", "110", "1", "1",
                                                "0", "0", "0"),
                                        row(
                                                13, "171", "100", "35", "40", "70", "120", "1", "2",
                                                "1", "2", "0"))),
                        table(
                                "SpellName",
                                "782EE721",
                                List.of(t("Name_lang")),
                                List.of(row(100, "Authored Potion"), row(101, "Authored Enchant"))),
                        table(
                                "SpellMisc",
                                "434B3607",
                                List.of(
                                        r("SpellID", "Spell"),
                                        n("DifficultyID"),
                                        n("SpellIconFileDataID")),
                                List.of(row(1, "100", "0", "2000"), row(2, "101", "0", "2001")))));
    }

    private static MemoryTables.FakeTable reagents(String hash, String count) {
        return table(
                "SpellReagents",
                hash,
                List.of(r("SpellID", "Spell"), a("Reagent", 8, "Item"), a("ReagentCount", 8, null)),
                List.of(
                        row(
                                1, "100", "2447", "2447", "0", "0", "0", "0", "0", "0", count, "3",
                                "0", "0", "0", "0", "0", "0")));
    }

    private static MemoryTables.FakeTable effects(List<Db2Types.Row> rows) {
        return table(
                "SpellEffect",
                "5362E3D4",
                List.of(
                        r("SpellID", "Spell"),
                        n("DifficultyID"),
                        n("EffectIndex"),
                        n("Effect"),
                        n("EffectItemType"),
                        a("EffectMiscValue", 2, null),
                        f("EffectBasePointsF")),
                rows);
    }

    private static MemoryTables.FakeTable tools() {
        return table(
                "SpellTotems",
                "03B23619",
                List.of(
                        r("SpellID", "Spell"),
                        a("Totem", 2, null),
                        a("RequiredTotemCategoryID", 2, null)),
                List.of(row(1, "100", "6217", "0", "1", "0")));
    }

    private static BuildTables open(List<MemoryTables.FakeTable> fixtures) {
        return new MemoryTables()
                .add(BUILD, "enUS", fixtures)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static List<MemoryTables.FakeTable> full() {
        var tables = core();
        tables.add(reagents("FF40D202", "2"));
        tables.add(tools());
        tables.add(
                effects(
                        List.of(
                                row(1, "100", "0", "0", "24", "118", "0", "0", "2"),
                                row(2, "101", "0", "0", "53", "0", "15", "0", "0"))));
        return tables;
    }

    @Test
    void catalogueKeepsGatheringAndSecondaryProfessionsWithoutInventingRecipes() {
        try (var tables = open(core());
                var service = new ProfessionsService(tables)) {
            var result = service.catalog(Cancellation.NONE).join();
            assertEquals(ProfessionTypes.State.READY, result.state());
            assertEquals(
                    List.of("Alchemy", "Cooking", "Herbalism"),
                    result.professions().stream().map(ProfessionTypes.Profession::name).toList());
            assertEquals(3, result.professions().getFirst().abilityCount());
            assertEquals(0, result.professions().get(2).abilityCount());
            assertEquals("Potions", result.categories().getFirst().name());
        }
    }

    @Test
    void otherClientSkillRecordsAreRetainedWithoutBecomingDefaultClassicProfessions() {
        var fixture = core();
        var skills = fixture.removeFirst();
        var rows = new ArrayList<>(skills.rows());
        rows.add(row(2937, "Internal Alchemy tier", "Internal", "11", "1000", "171"));
        rows.add(row(148, "Horse Riding", "Riding", "9", "1000", "0"));
        fixture.add(
                new MemoryTables.FakeTable(
                        skills.entry(), skills.columns(), rows, skills.schema(), Map.of(), true));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var result = service.catalog(Cancellation.NONE).join();
            assertEquals(5, result.professions().size());
            assertEquals(
                    3,
                    result.professions().stream()
                            .filter(ProfessionTypes.Profession::classicProfession)
                            .count());
            assertTrue(
                    result.professions().stream()
                            .anyMatch(p -> p.id() == 148 && !p.classicProfession()));
            assertTrue(
                    result.professions().stream()
                            .anyMatch(p -> p.id() == 2937 && !p.classicProfession()));
        }
    }

    @Test
    void pagingKeepsAbilityIdentityWhenTwoRowsReferenceOneSpell() {
        try (var tables = open(core());
                var service = new ProfessionsService(tables)) {
            var first = service.abilities(171, "", -1, 2, Cancellation.NONE).join();
            assertEquals(
                    List.of(11L, 12L),
                    first.abilities().stream().map(ProfessionTypes.Ability::id).toList());
            assertEquals(12L, first.nextAfter());
            var next = service.abilities(171, "", first.nextAfter(), 2, Cancellation.NONE).join();
            assertEquals(13, next.abilities().getFirst().id());
            assertNull(next.nextAfter());
            assertEquals(
                    List.of(11L, 13L),
                    service
                            .abilities(171, "potion", -1, 100, Cancellation.NONE)
                            .join()
                            .abilities()
                            .stream()
                            .map(ProfessionTypes.Ability::id)
                            .toList());
        }
    }

    @Test
    void recipeKeepsReagentSlotsToolsAndRawThresholdFacts() {
        try (var tables = open(full());
                var service = new ProfessionsService(tables)) {
            var detail = service.detail(11, Cancellation.NONE).join();
            assertEquals(ProfessionTypes.State.READY, detail.state());
            assertEquals(
                    List.of(0, 1),
                    detail.reagents().stream().map(ProfessionTypes.Ingredient::slot).toList());
            assertEquals(
                    List.of(2L, 3L),
                    detail.reagents().stream().map(ProfessionTypes.Ingredient::count).toList());
            assertEquals(6217L, detail.tools().getFirst().itemId());
            assertEquals(1L, detail.tools().getFirst().totemCategoryId());
            assertEquals(25L, detail.skill().minimumRank());
            assertEquals(50L, detail.skill().trivialLow());
            assertEquals(100L, detail.skill().trivialHigh());
            assertEquals(0L, detail.skill().acquireMethod());
            assertEquals(2000L, detail.ability().iconFileDataId());
            assertTrue(
                    detail.reagents().getFirst().sources().stream()
                            .anyMatch(
                                    s ->
                                            s.column().equals("ReagentCount[0]")
                                                    && s.value().equals("2")));
        }
    }

    @Test
    void directOutputDoesNotTurnRecordedBaseIntoGuaranteedYield() {
        try (var tables = open(full());
                var service = new ProfessionsService(tables)) {
            var result = service.detail(11, Cancellation.NONE).join().results().getFirst();
            assertEquals(ProfessionTypes.ResultKind.ITEM, result.kind());
            assertEquals(118L, result.itemId());
            assertEquals("2", result.recordedBaseAmount().toPlainString());
            assertNull(result.quantity());
        }
    }

    @Test
    void enchantmentsRemainInspectableWithoutFakeInventoryItem() {
        try (var tables = open(full());
                var service = new ProfessionsService(tables)) {
            var result = service.detail(12, Cancellation.NONE).join().results().getFirst();
            assertEquals(ProfessionTypes.ResultKind.ENCHANTMENT, result.kind());
            assertEquals(15L, result.enchantmentId());
            assertNull(result.itemId());
        }
    }

    @Test
    void unknownOptionalLayoutDoesNotDiscardCoreSkillOrOutputFacts() {
        var fixture = full();
        fixture.removeIf(t -> t.entry().name().equals("SpellReagents"));
        fixture.add(reagents("DEADBEEF", "2"));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var detail = service.detail(11, Cancellation.NONE).join();
            assertEquals(ProfessionTypes.State.PARTIAL, detail.state());
            assertEquals(ProfessionTypes.State.UNSUPPORTED, detail.reagentState());
            assertEquals(118L, detail.results().getFirst().itemId());
            assertEquals(25L, detail.skill().minimumRank());
        }
    }

    @Test
    void partialSourceRetainsReadableRowsAndDoesNotClaimCompleteReagents() {
        var fixture = full();
        var original =
                fixture.stream()
                        .filter(t -> t.entry().name().equals("SpellReagents"))
                        .findFirst()
                        .orElseThrow();
        fixture.remove(original);
        var e = original.entry();
        var partial =
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(2, 1, 1, 1, "fixture", "FF40D202"),
                        e.contentKey());
        fixture.add(
                new MemoryTables.FakeTable(
                        partial,
                        original.columns(),
                        original.rows(),
                        original.schema(),
                        Map.of(),
                        true));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var detail = service.detail(11, Cancellation.NONE).join();
            assertEquals(2, detail.reagents().size());
            assertEquals(ProfessionTypes.State.PARTIAL, detail.reagentState());
            assertEquals(ProfessionTypes.State.PARTIAL, detail.state());
        }
    }

    @Test
    void unreadableQuantityPreservesItemAndOtherSlots() {
        var fixture = full();
        fixture.removeIf(t -> t.entry().name().equals("SpellReagents"));
        fixture.add(reagents("FF40D202", "bad"));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var detail = service.detail(11, Cancellation.NONE).join();
            assertEquals(2, detail.reagents().size());
            assertNull(detail.reagents().getFirst().count());
            assertEquals(3L, detail.reagents().get(1).count());
        }
    }

    @Test
    void randomResultAndDuplicateEffectsNeverInventConcreteOutput() {
        var fixture = full();
        fixture.removeIf(t -> t.entry().name().equals("SpellEffect"));
        fixture.add(effects(List.of(row(1, "100", "0", "0", "59", "0", "0", "0", "0"))));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var result = service.detail(11, Cancellation.NONE).join().results().getFirst();
            assertEquals(ProfessionTypes.ResultKind.RANDOM_OR_SCRIPTED, result.kind());
            assertNull(result.itemId());
            assertNull(result.quantity());
        }
        fixture.removeIf(t -> t.entry().name().equals("SpellEffect"));
        fixture.add(
                effects(
                        List.of(
                                row(1, "100", "0", "0", "24", "118", "0", "0", "1"),
                                row(2, "100", "0", "0", "24", "119", "0", "0", "1"))));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var detail = service.detail(11, Cancellation.NONE).join();
            assertTrue(detail.results().isEmpty());
            assertEquals(ProfessionTypes.State.PARTIAL, detail.resultState());
        }
    }

    @Test
    void missingSpellNamesKeepStableIdFallbackAndRecipe() {
        var fixture = full();
        fixture.removeIf(t -> t.entry().name().equals("SpellName"));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            var result = service.abilities(171, "100", -1, 100, Cancellation.NONE).join();
            assertEquals(2, result.abilities().size());
            assertEquals("Spell #100", result.abilities().getFirst().name());
            assertEquals(ProfessionTypes.State.PARTIAL, result.state());
        }
    }

    @Test
    void unsupportedCoreLayoutIsExplicitRatherThanSilentlyGuessing() {
        var fixture = core();
        var table = fixture.removeFirst();
        fixture.add(
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                table.entry().fileDataId(),
                                "SkillLine",
                                TableTypes.State.READY,
                                true,
                                null,
                                new TableTypes.Counts(4, 4, 0, 0, "fixture", "BAD00000"),
                                "c".repeat(32)),
                        table.columns(),
                        table.rows(),
                        table.schema(),
                        Map.of(),
                        true));
        try (var tables = open(fixture);
                var service = new ProfessionsService(tables)) {
            assertEquals(
                    ProfessionTypes.State.UNSUPPORTED,
                    service.catalog(Cancellation.NONE).join().state());
        }
    }

    @Test
    void repeatedRecipeInspectionDoesNotRescanEntireRelatedTables() {
        try (var tables = open(full())) {
            var scans = new java.util.concurrent.atomic.AtomicInteger();
            var borrowed =
                    (BuildTables)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    BuildTables.class.getClassLoader(),
                                    new Class<?>[] {BuildTables.class},
                                    (proxy, method, arguments) -> {
                                        if (method.getName().equals("page")) {
                                            var request = (TableTypes.PageRequest) arguments[0];
                                            if (request.limit() > 1
                                                    && List.of("SpellReagents", "SpellEffect")
                                                            .contains(request.table().name())) {
                                                scans.incrementAndGet();
                                            }
                                        }
                                        try {
                                            return method.invoke(tables, arguments);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException
                                                        failure) {
                                            throw failure.getCause();
                                        }
                                    });
            try (var service = new ProfessionsService(borrowed)) {
                assertEquals(0, scans.get());
                service.detail(11, Cancellation.NONE).join();
                int prepared = scans.get();
                assertTrue(prepared > 0);
                service.detail(13, Cancellation.NONE).join();
                assertEquals(prepared, scans.get());
            }
        }
    }

    @Test
    void cancellationClosureAndQueryValidationRespectBorrowedHandle() {
        try (var tables = open(core())) {
            var service = new ProfessionsService(tables);
            var cancelled = new Cancellation();
            cancelled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.catalog(cancelled).join());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.abilities(171, "", -1, 101, Cancellation.NONE));
            assertEquals(
                    ProfessionTypes.State.READY, service.catalog(Cancellation.NONE).join().state());
            service.closeAsync().join();
            service.closeAsync().join();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.catalog(Cancellation.NONE).join());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("SkillLine"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }
}
