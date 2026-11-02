package com.vibeminer.concepts.creatures;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.DbdTypes;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Authored synthetic fields match the pinned C# CreaturesWorld. */
final class CreatureWorld {
    static final BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
    final MemoryTables tables = new MemoryTables();
    final MemoryFiles files = new MemoryFiles();
    final CreaturesService service =
            new CreaturesService(tables, files, new BlpImages(new MemoryGeneratedStore()));

    static DbdTypes.Field f(String name, String family, Integer bits, Boolean unsigned, int array) {
        return new DbdTypes.Field(
                new DbdTypes.Column(name, family, true, null, null),
                bits,
                unsigned,
                array,
                name.equals("ID"),
                false,
                false,
                null);
    }

    static List<DbdTypes.Field> creatureFields() {
        return List.of(
                f("ID", "int", 32, false, 1),
                f("Name_lang", "locstring", null, null, 1),
                f("NameAlt_lang", "locstring", null, null, 1),
                f("Title_lang", "locstring", null, null, 1),
                f("TitleAlt_lang", "locstring", null, null, 1),
                f("Classification", "int", 8, false, 1),
                f("CreatureType", "int", 8, true, 1),
                f("CreatureFamily", "int", 16, true, 1),
                f("StartAnimState", "int", 8, false, 1),
                f("DisplayID", "int", 32, false, 4),
                f("DisplayProbability", "float", null, null, 4));
    }

    static List<DbdTypes.Field> displayFields() {
        return List.of(
                f("ID", "int", 32, false, 1),
                f("ModelID", "int", 16, true, 1),
                f("SizeClass", "int", 8, false, 1),
                f("CreatureModelScale", "float", null, null, 1),
                f("ExtendedDisplayInfoID", "int", 32, false, 1),
                f("TextureVariationFileDataID", "int", 32, false, 4));
    }

    static List<DbdTypes.Field> modelFields() {
        return List.of(
                f("ID", "int", 32, false, 1),
                f("FileDataID", "int", 32, true, 1),
                f("ModelScale", "float", null, null, 1),
                f("CollisionHeight", "float", null, null, 1));
    }

    static MemoryTables.FakeTable table(
            long id,
            String name,
            String layout,
            List<DbdTypes.Field> fields,
            List<Db2Types.Row> rows,
            TableTypes.State state,
            Map<Long, String> protectedIds) {
        var columns = new ArrayList<Db2Types.Column>();
        for (var field : fields) {
            for (int i = 0; i < field.arrayLength(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.column().name()
                                        + (field.arrayLength() == 1 ? "" : "[" + i + "]"),
                                field.column().family().equals("int")
                                        ? "integer"
                                        : field.column().family().equals("float")
                                                ? "float"
                                                : "string",
                                field.isId(),
                                null,
                                null));
            }
        }
        var schema =
                TableSchema.fromBlock(
                        new DbdTypes.Block(List.of(layout), List.of(), fields, null),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US);
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        id,
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "WDC5", layout),
                        com.vibeminer.formats.internal.Json.sha256(
                                        rows.toString()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .substring(0, 32)),
                columns,
                rows,
                schema,
                protectedIds,
                false);
    }

    static MemoryTables.FakeTable creatures(List<Db2Types.Row> rows) {
        return table(
                841631,
                "Creature",
                "6E14C900",
                creatureFields(),
                rows,
                TableTypes.State.READY,
                Map.of());
    }

    static MemoryTables.FakeTable displays(List<Db2Types.Row> rows) {
        return table(
                1108759,
                "CreatureDisplayInfo",
                "7275F5F6",
                displayFields(),
                rows,
                TableTypes.State.READY,
                Map.of());
    }

    static MemoryTables.FakeTable models(List<Db2Types.Row> rows) {
        return table(
                1365368,
                "CreatureModelData",
                "0F5449F8",
                modelFields(),
                rows,
                TableTypes.State.READY,
                Map.of());
    }

    static Db2Types.Row creature(long id, String name, int... displays) {
        var values =
                new ArrayList<>(
                        List.of(
                                Long.toString(id),
                                name,
                                "Alt",
                                "Title",
                                "",
                                "-128",
                                "255",
                                "65535",
                                "127"));
        for (int i = 0; i < 4; i++) {
            values.add(Integer.toString(i < displays.length ? displays[i] : 0));
        }
        values.addAll(List.of("1", "0", "0", "0"));
        return new Db2Types.Row(id, false, null, values, null);
    }

    static Db2Types.Row display(long id, String model) {
        return new Db2Types.Row(
                id,
                false,
                null,
                List.of(Long.toString(id), model, "2", "1.5", "0", "800", "0", "0", "0"),
                null);
    }

    static Db2Types.Row model(long id, String file) {
        return new Db2Types.Row(
                id, false, null, List.of(Long.toString(id), file, "1.25", "2.25"), null);
    }

    CreatureWorld with(MemoryTables.FakeTable... tables) {
        this.tables.add(BUILD, "enUS", List.of(tables));
        return this;
    }

    BuildCreatures open() {
        return service.forBuild(BUILD, "enUS", Cancellation.NONE).join().creatures();
    }
}
