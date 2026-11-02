package com.vibeminer.formats.tables;

import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.util.List;
import java.util.Map;

final class MemoryTablesContractTest implements TablesContract {
    @Override
    public Tables create() {
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var schema =
                TableSchema.fromBlock(
                        definition.blocks().getFirst(),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US);
        var entry =
                new TableTypes.Entry(
                        100,
                        "Numbers",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(2, 2, 0, 0, "WDC5", "01020304"),
                        "1".repeat(32));
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                new MemoryTables.FakeTable(
                                        entry,
                                        List.of(
                                                new Db2Types.Column(
                                                        "ID", "integer", true, null, null),
                                                new Db2Types.Column(
                                                        "Value", "integer", false, null, null)),
                                        List.of(
                                                new Db2Types.Row(
                                                        7,
                                                        false,
                                                        null,
                                                        List.of("7", "9"),
                                                        List.of()),
                                                new Db2Types.Row(
                                                        8, true, 7L, List.of("8", "9"), List.of())),
                                        schema,
                                        Map.of(),
                                        false)));
    }
}
