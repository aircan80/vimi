package com.vibeminer.store;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.vibeminer.store.StoreAssertions.*;

final class CompactionInteropTest {
    @TempDir Path folder;
    List<String> command(String... args) {
        List<String> command=new ArrayList<>(List.of(System.getProperty("vimi.dotnet"),System.getProperty("vimi.interop.harness")));
        command.addAll(List.of(args));return command;
    }
    @ParameterizedTest @EnumSource(value=MaintenancePoint.class,names={"COMPACT_TARGET_LISTED","COMPACT_COPYING","COMPACT_COPIED","COMPACT_BEFORE_SWITCH","COMPACT_SWITCHING","COMPACT_SWITCHED","COMPACT_OLD_DELETED","COMPACT_SOURCE_UNLISTED"})
    void csharpRecoversJavaCompactionAtEachBoundary(MaintenancePoint point) throws Exception {
        CompactionFixture.prepare(folder);CrashProcess.killJava(folder,point.name(),"compact");
        Path output=folder.resolveSibling(folder.getFileName()+"-compaction-output.txt");
        try {
            Process process=new ProcessBuilder(command("recover-compaction",folder.toString())).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(output));}
            finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
        } finally {Files.deleteIfExists(output);}
        verifyJava();
    }
    @ParameterizedTest @ValueSource(strings={"CompactTargetListed","CompactCopied","CompactSwitched","CompactOldDeleted"})
    void javaRecoversCsharpCompactionAtEachBoundary(String point) throws Exception {
        CompactionFixture.prepare(folder);
        CrashProcess.killAtReady(command("crash-compactor",folder.toString(),point),"READY "+point);
        verifyJava();
    }
    private void verifyJava() {
        ObjectStore store=ObjectStore.open(folder).join();
        try {
            assertArrayEquals(CompactionFixture.kept(),store.read(key(CompactionFixture.kept()),NONE).join().bytes());
            assertFalse(store.has(key(CrashFixture.baseline()),NONE).join());assertFalse(store.has(key(CompactionFixture.dead()),NONE).join());
            assertEquals(1,store.readHistory(new History.Query(),NONE).join().size());
            assertEquals(0,store.database.scalar("SELECT count(*) FROM packs WHERE state>=2"));
            assertEquals(3,store.scan(NONE).join().live());assertNull(store.scrub(true,NONE).join().indexProblem());
        } finally {store.closeAsync().join();}
    }
}
