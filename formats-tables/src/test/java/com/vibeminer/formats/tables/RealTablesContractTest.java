package com.vibeminer.formats.tables;

import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class RealTablesContractTest implements TablesContract {
    @Override
    public Tables create() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var file =
                new FileTypes.BuildFile(
                        100,
                        "dbfilesclient/numbers.db2",
                        FileTypes.FilePathSource.LISTFILE,
                        (long) bytes.length,
                        "1".repeat(32),
                        FileTypes.FileFormat.DB2,
                        FileTypes.FileFormatSource.EXTENSION,
                        FileTypes.FileStorage.STORED,
                        false);
        var files = new MemoryFiles().add(BUILD, "enUS", file, bytes, List.of());
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var provider =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Numbers", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", definition)));
        return new TablesService(
                files,
                new TableDefinitions(provider, null, null),
                new GeneratedTables(new MemoryGeneratedStore()),
                new TablesOptions((build, token) -> CompletableFuture.completedFuture("1.2.3.4")));
    }
}
