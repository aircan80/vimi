package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RaceAppearancePlanTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            String name, String layout, String fields, Db2Types.Row... rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (String declaration : fields.split(",")) {
            var relation = declaration.split("->");
            var parts = relation[0].split(":");
            String field = parts[0];
            int count = parts.length == 1 ? 1 : Integer.parseInt(parts[1]);
            schema.add(
                    new TableSchema.SchemaColumn(
                            field,
                            TableSchema.ColumnKind.INT,
                            32,
                            true,
                            count,
                            relation.length == 1
                                    ? null
                                    : new TableSchema.ForeignKey(relation[1], "ID"),
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            false,
                            WowLocale.parse("enUS"),
                            null,
                            null));
            for (int i = 0; i < count; i++) {
                columns.add(
                        new Db2Types.Column(
                                count == 1 ? field : field + "[" + i + "]",
                                "integer",
                                false,
                                null,
                                null));
            }
        }
        var state =
                name.equals("ItemDisplayInfo") ? TableTypes.State.PARTIAL : TableTypes.State.READY;
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        Integer.toUnsignedLong(name.hashCode()),
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(
                                rows.length,
                                rows.length,
                                state == TableTypes.State.PARTIAL ? 1 : 0,
                                state == TableTypes.State.PARTIAL ? 1 : 0,
                                "fixture",
                                layout),
                        "c".repeat(32)),
                columns,
                List.of(rows),
                new TableSchema(
                        schema,
                        List.of(layout),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static List<MemoryTables.FakeTable> fixture(boolean ambiguous, int purpose) {
        var textures =
                new ArrayList<Db2Types.Row>(
                        List.of(
                                row(10000, "0", "900"),
                                row(10001, "0", "900"),
                                row(10003, "0", "900")));
        var components =
                new ArrayList<Db2Types.Row>(
                        List.of(
                                row(10000, "0", "0", "0"),
                                row(10001, "1", "0", "0"),
                                row(10003, "3", "0", "0")));
        if (ambiguous) {
            textures.add(row(10002, "0", "900"));
            components.add(row(10002, "0", "0", "0"));
        }
        return List.of(
                table("ChrRaces", "4F44C796", "PlayableRaceBit", row(1, "0")),
                table(
                        "ChrModel",
                        "03FAB755",
                        "CharComponentTextureLayoutID",
                        row(1, "103"),
                        row(2, "103")),
                table(
                        "ChrModelMaterial",
                        "22469480",
                        "CharComponentTextureLayoutsID,TextureType,Width,Height,Flags",
                        row(1, "103", "1", "2", "1", "0")),
                table(
                        "ChrModelTextureLayer",
                        "D0583FB4",
                        "CharComponentTextureLayoutsID,TextureType,Layer,BlendMode,TextureSectionTypeBitMask,TextureSectionTypeBitMask2,Flags,ChrModelTextureTargetID:2"),
                table(
                        "CharComponentTextureSections",
                        "2173BA71",
                        "CharComponentTextureLayoutID,SectionType,X,Y,Width,Height",
                        row(1, "103", "3", "0", "0", "2", "1")),
                table("ChrCustomizationOption", "DCC2A86E", "ChrModelID,Requirement"),
                table(
                        "ChrCustomizationChoice",
                        "9559C358",
                        "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID"),
                table(
                        "ChrCustomizationElement",
                        "6483C37E",
                        "ChrCustomizationChoiceID,RelatedChrCustomizationChoiceID,ChrCustomizationMaterialID"),
                table(
                        "ChrCustomizationReq",
                        "CA154412",
                        "ReqType,ClassMask,RegionGroupMask,ReqAchievementID,ReqQuestID,OverrideArchive,ReqItemModifiedAppearanceID,RaceMasks:2"),
                table("ChrCustomizationReqChoice", "F925BC6F", "ChrCustomizationReqID"),
                table(
                        "ChrCustomizationMaterial",
                        "BE9767E9",
                        "ChrModelTextureTargetID,MaterialResourcesID"),
                table(
                        "CharacterLoadout",
                        "713CE8BB",
                        "ChrClassID,Purpose,Racemask:2",
                        row(1, "1", Integer.toString(purpose), "1", "0")),
                table(
                        "CharacterLoadoutItem",
                        "0C7A1862",
                        "CharacterLoadoutID,ItemID",
                        row(1, "1", "100")),
                table("Item", "9A2A4834", "InventoryType", row(100, "4")),
                table(
                        "ItemModifiedAppearance",
                        "03A6C979",
                        "ItemID,ItemAppearanceModifierID,ItemAppearanceID",
                        row(1, "100", "0", "500")),
                table("ItemAppearance", "481C4281", "ItemDisplayInfoID", row(500, "600")),
                table(
                        "ItemDisplayInfo",
                        "9F3AB8A9",
                        "GeosetGroupOverride,GeosetGroup:6,ModelResourcesID:2",
                        row(600, "0", "0", "0", "0", "0", "0", "0", "0", "0")),
                table(
                        "ItemDisplayInfoMaterialRes",
                        "AA462C0E",
                        "ItemDisplayInfoID,ComponentSection,MaterialResourcesID",
                        row(700, "600", "3", "900")),
                table(
                        "TextureFileData",
                        "BD7C74C2",
                        "UsageType,MaterialResourcesID",
                        textures.toArray(Db2Types.Row[]::new)),
                table(
                        "ComponentTextureFileData",
                        "B32B030A",
                        "GenderIndex,ClassID,RaceID",
                        components.toArray(Db2Types.Row[]::new)));
    }

    @Test
    void classClothingUsesPurposeNineExactSexAndReadablePartialDisplayRows() {
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", fixture(false, 9))
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var media = new RaceMedia(service, tables, null, null);
            var plans = new RaceAppearancePlan(service, media, tables);
            var notes = new ArrayList<String>();
            var male =
                    plans.build(
                            1,
                            1,
                            new RaceMediaTypes.Variant(1, 0, 1, 1, 1),
                            Cancellation.NONE,
                            notes);
            assertEquals(10000, male.layers().getFirst().fileDataId());
            assertEquals(100, male.items().getFirst().itemId());
            assertEquals(15, male.layers().getFirst().blend());
            assertTrue(notes.stream().anyMatch(note -> note.contains("partial")));
            var female =
                    plans.build(
                            1,
                            1,
                            new RaceMediaTypes.Variant(2, 1, 1, 1, 1),
                            Cancellation.NONE,
                            new ArrayList<>());
            assertEquals(10001, female.layers().getFirst().fileDataId());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void ambiguousDiffuseFilesAndOtherLoadoutPurposesAreNeverGuessed() {
        for (int purpose : new int[] {1, 9}) {
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", fixture(true, purpose))
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                var notes = new ArrayList<String>();
                var plan =
                        new RaceAppearancePlan(
                                        service, new RaceMedia(service, tables, null, null), tables)
                                .build(
                                        1,
                                        1,
                                        new RaceMediaTypes.Variant(1, 0, 1, 1, 1),
                                        Cancellation.NONE,
                                        notes);
                assertTrue(plan.layers().isEmpty());
                assertTrue(notes.stream().anyMatch(note -> note.contains("unique")));
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    private static void replace(
            List<MemoryTables.FakeTable> values, MemoryTables.FakeTable replacement) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).entry().name().equals(replacement.entry().name())) {
                values.set(i, replacement);
                return;
            }
        }
        throw new AssertionError("Missing fixture table");
    }

    private static MemoryTables.FakeTable coverage(
            MemoryTables.FakeTable value, TableTypes.State state, Map<Long, String> protectedIds) {
        var e = value.entry();
        var counts = e.counts();
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        state,
                        true,
                        null,
                        new TableTypes.Counts(
                                value.rows().size() + (state == TableTypes.State.PARTIAL ? 1 : 0),
                                value.rows().size(),
                                state == TableTypes.State.PARTIAL ? 1 : 0,
                                state == TableTypes.State.PARTIAL ? 1 : 0,
                                counts.format(),
                                counts.layoutHash()),
                        e.contentKey()),
                value.columns(),
                value.rows(),
                value.schema(),
                protectedIds,
                true);
    }

    @Test
    void unknownAndProtectedTextureQualifiersAreNotUntaggedButProvenAlternativesRemain() {
        for (int kind = 0; kind < 3; kind++) {
            var seed = new ArrayList<>(fixture(false, 9));
            replace(
                    seed,
                    table(
                            "TextureFileData",
                            "BD7C74C2",
                            "UsageType,MaterialResourcesID",
                            row(10000, "0", "900"),
                            row(10001, "0", "900"),
                            row(20000, "0", "901")));
            var qualifiers =
                    table(
                            "ComponentTextureFileData",
                            "B32B030A",
                            "GenderIndex,ClassID,RaceID",
                            row(10001, "1", "0", "0"),
                            row(20000, "0", "0", "0"));
            replace(
                    seed,
                    coverage(
                            qualifiers,
                            kind == 0 ? TableTypes.State.READY : TableTypes.State.PARTIAL,
                            kind == 2 ? Map.of(10000L, "A".repeat(16)) : Map.of()));
            replace(
                    seed,
                    table(
                            "ItemDisplayInfoMaterialRes",
                            "AA462C0E",
                            "ItemDisplayInfoID,ComponentSection,MaterialResourcesID",
                            row(700, "600", "3", "900"),
                            row(701, "600", "3", "901")));
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", seed)
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                var notes = new ArrayList<String>();
                var plan =
                        new RaceAppearancePlan(
                                        service, new RaceMedia(service, tables, null, null), tables)
                                .build(
                                        1,
                                        1,
                                        new RaceMediaTypes.Variant(1, 0, 1, 1, 1),
                                        Cancellation.NONE,
                                        notes);
                assertEquals(
                        kind == 0 ? java.util.Set.of(10000L, 20000L) : java.util.Set.of(20000L),
                        plan.layers().stream()
                                .map(RaceAppearancePlan.Layer::fileDataId)
                                .collect(java.util.stream.Collectors.toSet()));
                if (kind > 0) {
                    assertTrue(
                            notes.stream()
                                    .anyMatch(note -> note.contains("qualifiers are unknown")));
                }
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void partialPrerequisiteAbsenceIsUnknownAndSelectsProvenUnrestrictedAlternative() {
        for (var state : List.of(TableTypes.State.READY, TableTypes.State.PARTIAL)) {
            var seed = new ArrayList<>(fixture(false, 9));
            replace(
                    seed,
                    table(
                            "ChrCustomizationOption",
                            "DCC2A86E",
                            "ChrModelID,Requirement",
                            row(10, "1", "0")));
            replace(
                    seed,
                    table(
                            "ChrCustomizationChoice",
                            "9559C358",
                            "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID",
                            row(1, "10", "0", "1", "0"),
                            row(2, "10", "1", "0", "0")));
            replace(
                    seed,
                    coverage(
                            table(
                                    "ChrCustomizationReq",
                                    "CA154412",
                                    "ReqType,ClassMask,RegionGroupMask,ReqAchievementID,ReqQuestID,OverrideArchive,ReqItemModifiedAppearanceID,RaceMasks:2",
                                    row(1, "3", "-1", "0", "0", "0", "-1", "0", "-1", "-1")),
                            TableTypes.State.PARTIAL,
                            Map.of()));
            replace(
                    seed,
                    coverage(
                            table("ChrCustomizationReqChoice", "F925BC6F", "ChrCustomizationReqID"),
                            state,
                            Map.of()));
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", seed)
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                var notes = new ArrayList<String>();
                var selected =
                        new RaceCustomization(new RaceMedia(service, tables, null, null))
                                .select(1, 1, 1, Cancellation.NONE, notes);
                assertEquals(
                        java.util.Set.of(state == TableTypes.State.READY ? 1L : 2L),
                        selected.choices());
                if (state == TableTypes.State.PARTIAL) {
                    assertTrue(
                            notes.stream()
                                    .anyMatch(note -> note.contains("prerequisite requirements")));
                }
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void stableSampleUsesRecordedIdsWithoutAdmittingRawUiOrderSemantics() {
        var seed = new ArrayList<>(fixture(false, 9));
        replace(
                seed,
                table(
                        "ChrCustomizationOption",
                        "DCC2A86E",
                        "ChrModelID,Requirement",
                        row(10, "1", "0")));
        var choices =
                table(
                        "ChrCustomizationChoice",
                        "9559C358",
                        "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID",
                        row(1, "10", "65536", "0", "0"),
                        row(2, "10", "0", "0", "0"));
        var columns =
                choices.columns().stream()
                        .map(
                                column ->
                                        column.name().equals("UiOrderIndex")
                                                ? new Db2Types.Column(
                                                        column.name(),
                                                        column.kind(),
                                                        false,
                                                        new Db2Types.Interpretation(
                                                                "raw-physical-integer-v1",
                                                                32,
                                                                false,
                                                                16,
                                                                true,
                                                                "Unverified provider field preserves physical integers."),
                                                        null)
                                                : column)
                        .toList();
        replace(
                seed,
                new MemoryTables.FakeTable(
                        choices.entry(),
                        columns,
                        choices.rows(),
                        choices.schema(),
                        Map.of(),
                        true));
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", seed)
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var selected =
                    new RaceCustomization(new RaceMedia(service, tables, null, null))
                            .select(1, 1, 1, Cancellation.NONE, new ArrayList<>());
            assertEquals(java.util.Set.of(1L), selected.choices());
            var schema =
                    service.schema("ChrCustomizationChoice", Cancellation.NONE, new ArrayList<>());
            org.junit.jupiter.api.Assertions.assertThrows(
                    RacesService.Projection.class, () -> schema.field("UiOrderIndex", true));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void reviewedGeosetGroupKeepsFullPhysicalIntegerAndRejectsOtherRawWidths() {
        var field =
                new TableSchema.SchemaColumn(
                        "GeosetType",
                        TableSchema.ColumnKind.INT,
                        8,
                        false,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        false,
                        WowLocale.parse("enUS"),
                        null,
                        null);
        var schema =
                new TableSchema(
                        List.of(field),
                        List.of("BF55F2FB"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null);
        var entry =
                table("ChrCustomizationGeoset", "BF55F2FB", "GeosetType", row(1, "256")).entry();
        for (int bits : new int[] {32, 64}) {
            var column =
                    new Db2Types.Column(
                            "GeosetType",
                            "integer",
                            false,
                            new Db2Types.Interpretation(
                                    "raw-physical-integer-v1",
                                    bits,
                                    false,
                                    8,
                                    false,
                                    "Unverified physical group integer"),
                            null);
            var data =
                    new RacesService.Data(
                            "ChrCustomizationGeoset",
                            List.of(column),
                            schema,
                            List.of(row(1, "256")),
                            entry);
            if (bits == 32) {
                data.field("GeosetType", true);
                assertEquals(256, data.number(data.rows.getFirst(), "GeosetType"));
            } else {
                org.junit.jupiter.api.Assertions.assertThrows(
                        RacesService.Projection.class, () -> data.field("GeosetType", true));
            }
        }
        var wrongCandidate =
                new Db2Types.Column(
                        "GeosetType",
                        "integer",
                        false,
                        new Db2Types.Interpretation(
                                "raw-physical-integer-v1",
                                32,
                                false,
                                16,
                                false,
                                "Different raw candidate"),
                        null);
        var data =
                new RacesService.Data(
                        "ChrCustomizationGeoset",
                        List.of(wrongCandidate),
                        schema,
                        List.of(row(1, "256")),
                        entry);
        org.junit.jupiter.api.Assertions.assertThrows(
                RacesService.Projection.class, () -> data.field("GeosetType", true));
    }
}
