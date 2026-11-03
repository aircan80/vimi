package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static List<MemoryTables.FakeTable> orcFixture(int model, int layout) {
        var seed = new ArrayList<>(fixture(false, 9));
        replace(seed, table("ChrRaces", "4F44C796", "PlayableRaceBit", row(2, "1")));
        replace(
                seed,
                table(
                        "ChrModel",
                        "03FAB755",
                        "CharComponentTextureLayoutID",
                        row(model, Integer.toString(layout))));
        replace(
                seed,
                table(
                        "ChrModelMaterial",
                        "22469480",
                        "CharComponentTextureLayoutsID,TextureType,Width,Height,Flags",
                        row(1, Integer.toString(layout), "1", "2048", "1024", "0")));
        replace(
                seed,
                table(
                        "CharComponentTextureSections",
                        "2173BA71",
                        "CharComponentTextureLayoutID,SectionType,X,Y,Width,Height",
                        row(1, Integer.toString(layout), "3", "512", "0", "512", "256")));
        replace(
                seed,
                table(
                        "CharacterLoadout",
                        "713CE8BB",
                        "ChrClassID,Purpose,Racemask:2",
                        row(1, "1", "9", "2", "0")));
        return seed;
    }

    private static List<MemoryTables.FakeTable> taurenFixture(
            int model, int layout, int sex, String fallback) {
        var seed = new ArrayList<>(orcFixture(model, layout));
        replace(seed, table("ChrRaces", "4F44C796", "PlayableRaceBit", row(6, "5")));
        var modelTable =
                table(
                        "ChrModel",
                        "03FAB755",
                        "FaceCustomizationOffset:3,CustomizeOffset:3,ID,Sex,DisplayID,CharComponentTextureLayoutID,Flags,SkeletonFileDataID,ModelFallbackChrModelID,TextureFallbackChrModelID",
                        row(
                                model,
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                Integer.toString(model),
                                Integer.toString(sex),
                                "1",
                                Integer.toString(layout),
                                "1",
                                "0",
                                fallback,
                                "0"));
        var modelSchema =
                new TableSchema(
                        modelTable.schema().columns().stream()
                                .map(
                                        field ->
                                                new TableSchema.SchemaColumn(
                                                        field.name(),
                                                        field.kind(),
                                                        field.bits(),
                                                        field.name().endsWith("FallbackChrModelID")
                                                                ? false
                                                                : field.unsigned(),
                                                        field.arrayLength(),
                                                        field.foreignKey(),
                                                        field.id(),
                                                        field.relation(),
                                                        field.name().equals("Sex"),
                                                        field.locale(),
                                                        field.columnComment(),
                                                        field.fieldComment()))
                                .toList(),
                        modelTable.schema().layoutHashes(),
                        modelTable.schema().match(),
                        modelTable.schema().locale(),
                        null);
        replace(
                seed,
                new MemoryTables.FakeTable(
                        modelTable.entry(),
                        modelTable.columns(),
                        modelTable.rows(),
                        modelSchema,
                        Map.of(),
                        true));
        replace(
                seed,
                table(
                        "ChrModelMaterial",
                        "22469480",
                        "CharComponentTextureLayoutsID,TextureType,Width,Height,Flags",
                        row(1, Integer.toString(layout), "1", "2048", "1024", "0"),
                        row(2, Integer.toString(layout), "6", "512", "512", "0"),
                        row(3, Integer.toString(layout), "8", "512", "512", "0"),
                        row(4, Integer.toString(layout), "19", "256", "128", "0"),
                        row(5, Integer.toString(layout), "20", "256", "256", "0")));
        replace(
                seed,
                table(
                        "CharacterLoadout",
                        "713CE8BB",
                        "ChrClassID,Purpose,Racemask:2",
                        row(1, "1", "9", "32", "0")));
        replace(
                seed,
                table(
                        "ChrCustomizationOption",
                        "DCC2A86E",
                        "ChrModelID,Requirement",
                        row(10, Integer.toString(model), "0")));
        replace(
                seed,
                table(
                        "ChrCustomizationChoice",
                        "9559C358",
                        "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID",
                        row(20, "10", "0", "0", "0")));
        replace(
                seed,
                table(
                        "ChrCustomizationElement",
                        "6483C37E",
                        "ChrCustomizationChoiceID,RelatedChrCustomizationChoiceID,ChrCustomizationMaterialID",
                        row(30, "20", "0", "40")));
        replace(
                seed,
                table(
                        "ChrCustomizationMaterial",
                        "BE9767E9",
                        "ChrModelTextureTargetID,MaterialResourcesID",
                        row(40, "2", "900")));
        replace(
                seed,
                table(
                        "ChrModelTextureLayer",
                        "D0583FB4",
                        "CharComponentTextureLayoutsID,TextureType,Layer,BlendMode,TextureSectionTypeBitMask,TextureSectionTypeBitMask2,Flags,ChrModelTextureTargetID:2",
                        row(
                                50,
                                Integer.toString(layout),
                                "8",
                                "1",
                                "1",
                                "-1",
                                "-1",
                                "0",
                                "2",
                                "0")));
        return seed;
    }

    @Test
    void taurenPairUsesRecordedFurCanvasAndSexQualifiedDiffuseWithoutNewBounds() {
        for (int model : new int[] {11, 12}) {
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", taurenFixture(model, model + 102, model - 11, "0"))
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                var plan =
                        new RaceAppearancePlan(
                                        service, new RaceMedia(service, tables, null, null), tables)
                                .build(
                                        6,
                                        1,
                                        new RaceMediaTypes.Variant(model, model - 11, 1, 1, 1),
                                        Cancellation.NONE,
                                        new ArrayList<>());
                assertEquals(5, plan.canvases().size());
                var fur =
                        plan.canvases().stream()
                                .filter(canvas -> canvas.type() == 8)
                                .findFirst()
                                .orElseThrow();
                assertEquals(new RaceAppearancePlan.Canvas(8, 512, 512), fur);
                assertEquals(
                        new RaceAppearancePlan.Canvas(20, 256, 256),
                        plan.canvases().stream()
                                .filter(canvas -> canvas.type() == 20)
                                .findFirst()
                                .orElseThrow());
                var layer =
                        plan.layers().stream()
                                .filter(value -> value.type() == 8)
                                .findFirst()
                                .orElseThrow();
                assertEquals(model == 11 ? 10000 : 10001, layer.fileDataId());
                assertEquals(512, layer.width());
                assertEquals(512, layer.height());
                assertEquals(1, layer.blend());
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void taurenAdmissionRejectsWrongSexLayoutOrNonzeroCompatibilitySlot() {
        for (int[] mismatch : new int[][] {{11, 113, 1, 0}, {12, 113, 1, 0}, {11, 113, 0, 7}}) {
            var tables =
                    new MemoryTables()
                            .add(
                                    BUILD,
                                    "enUS",
                                    taurenFixture(
                                            mismatch[0],
                                            mismatch[1],
                                            mismatch[2],
                                            Integer.toString(mismatch[3])))
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                assertThrows(
                        RacesService.Projection.class,
                        () ->
                                new RaceAppearancePlan(
                                                service,
                                                new RaceMedia(service, tables, null, null),
                                                tables)
                                        .build(
                                                6,
                                                1,
                                                new RaceMediaTypes.Variant(
                                                        mismatch[0], mismatch[2], 1, 1, 1),
                                                Cancellation.NONE,
                                                new ArrayList<>()));
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
        assertTrue(
                !RaceAppearance.reviewedField(
                        "ChrModel", List.of("03FAB755"), "ModelFallbackChrModelID"));
        assertTrue(
                !RaceAppearance.reviewedField(
                        "ChrModel", List.of("03FAB755"), "TextureFallbackChrModelID"));
    }

    @Test
    void taurenCompatibilityGateRejectsChangedLogicalWidthSignednessOrRawInterpretation() {
        for (int change = 0; change < 3; change++) {
            var seed = new ArrayList<>(taurenFixture(11, 113, 0, "0"));
            var original =
                    seed.stream()
                            .filter(table -> table.entry().name().equals("ChrModel"))
                            .findFirst()
                            .orElseThrow();
            var fields = new ArrayList<>(original.schema().columns());
            var columns = new ArrayList<>(original.columns());
            int at =
                    java.util.stream.IntStream.range(0, fields.size())
                            .filter(i -> fields.get(i).name().equals("ModelFallbackChrModelID"))
                            .findFirst()
                            .orElseThrow();
            var field = fields.get(at);
            fields.set(
                    at,
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.kind(),
                            change == 0 ? 16 : 32,
                            change == 1,
                            field.arrayLength(),
                            field.foreignKey(),
                            field.id(),
                            field.relation(),
                            false,
                            field.locale(),
                            field.columnComment(),
                            field.fieldComment()));
            if (change == 2) {
                columns.set(
                        12,
                        new Db2Types.Column(
                                "ModelFallbackChrModelID",
                                "integer",
                                false,
                                new Db2Types.Interpretation(
                                        "raw-physical-integer-v1",
                                        32,
                                        false,
                                        16,
                                        false,
                                        "Authored unreviewed shape"),
                                null));
            }
            replace(
                    seed,
                    new MemoryTables.FakeTable(
                            original.entry(),
                            columns,
                            original.rows(),
                            new TableSchema(
                                    fields,
                                    original.schema().layoutHashes(),
                                    original.schema().match(),
                                    original.schema().locale(),
                                    null),
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
                assertThrows(
                        RacesService.Projection.class,
                        () ->
                                new RaceAppearancePlan(
                                                service,
                                                new RaceMedia(service, tables, null, null),
                                                tables)
                                        .build(
                                                6,
                                                1,
                                                new RaceMediaTypes.Variant(11, 0, 1, 1, 1),
                                                Cancellation.NONE,
                                                new ArrayList<>()));
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void orcPairUsesRecordedAtlasSectionsAndSexQualifiedClassClothing() {
        for (int model : new int[] {3, 4}) {
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", orcFixture(model, model + 102))
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                var plan =
                        new RaceAppearancePlan(
                                        service, new RaceMedia(service, tables, null, null), tables)
                                .build(
                                        2,
                                        1,
                                        new RaceMediaTypes.Variant(model, model - 3, 1, 1, 1),
                                        Cancellation.NONE,
                                        new ArrayList<>());
                assertEquals(2048, plan.canvases().getFirst().width());
                assertEquals(1024, plan.canvases().getFirst().height());
                var layer = plan.layers().getFirst();
                assertEquals(model == 3 ? 10000 : 10001, layer.fileDataId());
                assertEquals(512, layer.x());
                assertEquals(512, layer.width());
                assertEquals(256, layer.height());
                assertEquals(100, plan.items().getFirst().itemId());
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void orcCustomizationRetainsStrictReviewedBlendProfile() {
        for (int blend : new int[] {1, 15, 4, 6, 9}) {
            var seed = new ArrayList<>(orcFixture(3, 105));
            replace(
                    seed,
                    table(
                            "ChrCustomizationOption",
                            "DCC2A86E",
                            "ChrModelID,Requirement",
                            row(10, "3", "0")));
            replace(
                    seed,
                    table(
                            "ChrCustomizationChoice",
                            "9559C358",
                            "ChrCustomizationOptionID->ChrCustomizationOption,UiOrderIndex,ChrCustomizationReqID,ChrCustomizationVisReqID",
                            row(20, "10", "0", "0", "0")));
            replace(
                    seed,
                    table(
                            "ChrCustomizationElement",
                            "6483C37E",
                            "ChrCustomizationChoiceID,RelatedChrCustomizationChoiceID,ChrCustomizationMaterialID",
                            row(30, "20", "0", "40")));
            replace(
                    seed,
                    table(
                            "ChrCustomizationMaterial",
                            "BE9767E9",
                            "ChrModelTextureTargetID,MaterialResourcesID",
                            row(40, "1", "900")));
            replace(
                    seed,
                    table(
                            "ChrModelTextureLayer",
                            "D0583FB4",
                            "CharComponentTextureLayoutsID,TextureType,Layer,BlendMode,TextureSectionTypeBitMask,TextureSectionTypeBitMask2,Flags,ChrModelTextureTargetID:2",
                            row(
                                    50,
                                    "105",
                                    "1",
                                    "0",
                                    Integer.toString(blend),
                                    "-1",
                                    "-1",
                                    "0",
                                    "1",
                                    "0")));
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
                                        2,
                                        1,
                                        new RaceMediaTypes.Variant(3, 0, 1, 1, 1),
                                        Cancellation.NONE,
                                        notes);
                boolean supported = blend == 1 || blend == 15;
                assertEquals(
                        supported ? 1 : 0,
                        plan.layers().stream()
                                .filter(layer -> layer.source().startsWith("Customization"))
                                .count());
                assertTrue(plan.layers().stream().anyMatch(layer -> layer.fileDataId() == 10000));
                if (!supported) {
                    assertTrue(
                            notes.stream()
                                    .anyMatch(
                                            note ->
                                                    note.contains(
                                                            "outside the reviewed static profile")));
                }
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
    }

    @Test
    void orcAdmissionRejectsWrongRaceModelSexOrAtlas() {
        for (int[] mismatch :
                new int[][] {
                    {3, 2, 3, 0, 105}, {2, 2, 2, 1, 104}, {2, 3, 3, 1, 105}, {2, 4, 4, 1, 105}
                }) {
            var tables =
                    new MemoryTables()
                            .add(BUILD, "enUS", orcFixture(mismatch[1], mismatch[4]))
                            .forBuild(BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .tables();
            var service = new RacesService(tables);
            try {
                assertThrows(
                        RacesService.Projection.class,
                        () ->
                                new RaceAppearancePlan(
                                                service,
                                                new RaceMedia(service, tables, null, null),
                                                tables)
                                        .build(
                                                mismatch[0],
                                                1,
                                                new RaceMediaTypes.Variant(
                                                        mismatch[2], mismatch[3], 1, 1, 1),
                                                Cancellation.NONE,
                                                new ArrayList<>()));
            } finally {
                service.closeAsync().join();
                tables.closeAsync().join();
            }
        }
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
