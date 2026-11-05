package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class NativePublicationTest {
    private record Fixture(GeneratedTableSource source, GeneratedTableContent content) {}

    private static Fixture fixture(boolean keyed, Runnable close) {
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var limits = new Db2Types.Limits();
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(
                                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true)),
                                definition,
                                "1.2.3.4",
                                limits,
                                Cancellation.NONE)
                        .join();
        var block =
                definition
                        .selectFile("1.2.3.4", table.header().layoutHash(), Cancellation.NONE)
                        .blocks()
                        .getFirst();
        var source =
                GeneratedTableSource.forNativeBlock(
                        block, "enUS", "1".repeat(32), limits, "build-a");
        return new Fixture(
                source,
                GeneratedTableContent.nativeDb2(
                        source,
                        table,
                        keyed ? List.of("0000000000000001") : List.of(),
                        close::run,
                        List.of(),
                        List.of()));
    }

    @Test
    void preparatoryFailuresNeverPublishNativeNodeAndAlwaysCloseOwnerOnce() {
        for (String stage : List.of("layout", "alias", "readback", "owner", "final-before")) {
            var memory = new MemoryGeneratedStore();
            var closed = new AtomicInteger();
            var fixture =
                    fixture(
                            true,
                            () -> {
                                closed.incrementAndGet();
                                if (stage.equals("owner")) {
                                    throw new IllegalStateException("owner failure");
                                }
                            });
            var expected =
                    GeneratedKey.parse(
                            new GeneratedTables(memory)
                                    .identityOf(
                                            fixture.source(),
                                            TactKeyStatus.NONE,
                                            fixture.content().keysUsed(),
                                            false));
            var injected = intercept(memory, stage, expected);
            assertThrows(
                    CompletionException.class,
                    () ->
                            new GeneratedTables(injected)
                                    .store(
                                            fixture.source(),
                                            TactKeyStatus.NONE,
                                            fixture.content(),
                                            Cancellation.NONE)
                                    .join(),
                    stage);
            assertEquals(1, closed.get(), stage);
            assertTrue(!memory.keys().contains(expected), stage);
            assertNull(
                    new GeneratedTables(memory)
                            .openPreparedLocal(
                                    fixture.source(), TactKeyStatus.NONE, Cancellation.NONE)
                            .join(),
                    stage);
        }
    }

    @Test
    void lostFinalAcknowledgementReconcilesCommittedGraphWithoutDeletingIt() {
        var memory = new MemoryGeneratedStore();
        var closed = new AtomicInteger();
        var fixture = fixture(true, closed::incrementAndGet);
        var expected =
                GeneratedKey.parse(
                        new GeneratedTables(memory)
                                .identityOf(
                                        fixture.source(),
                                        TactKeyStatus.NONE,
                                        fixture.content().keysUsed(),
                                        false));
        var result =
                new GeneratedTables(intercept(memory, "final-after", expected))
                        .store(
                                fixture.source(),
                                TactKeyStatus.NONE,
                                fixture.content(),
                                Cancellation.NONE)
                        .join();
        assertEquals(1, closed.get());
        assertEquals(expected.hex(), result.identity());
        assertNotNull(result.issueCertificate());
        assertNotNull(
                new GeneratedTables(memory)
                        .openPreparedLocal(fixture.source(), TactKeyStatus.NONE, Cancellation.NONE)
                        .join());
    }

    @Test
    void unavailableReconciliationDoesNotDeleteAnActuallyCommittedGraph() {
        var memory = new MemoryGeneratedStore();
        var fixture = fixture(true, () -> {});
        var expected =
                GeneratedKey.parse(
                        new GeneratedTables(memory)
                                .identityOf(
                                        fixture.source(),
                                        TactKeyStatus.NONE,
                                        fixture.content().keysUsed(),
                                        false));
        assertThrows(
                CompletionException.class,
                () ->
                        new GeneratedTables(intercept(memory, "reconcile-unavailable", expected))
                                .store(
                                        fixture.source(),
                                        TactKeyStatus.NONE,
                                        fixture.content(),
                                        Cancellation.NONE)
                                .join());
        assertNotNull(
                new GeneratedTables(memory)
                        .openPreparedLocal(fixture.source(), TactKeyStatus.NONE, Cancellation.NONE)
                        .join(),
                "Ambiguous commit is not rolled back by deleting a potentially shared graph");
    }

    private static GeneratedStore intercept(
            MemoryGeneratedStore memory, String stage, GeneratedKey finalNode) {
        var committed = new AtomicBoolean();
        return (GeneratedStore)
                Proxy.newProxyInstance(
                        GeneratedStore.class.getClassLoader(),
                        new Class<?>[] {GeneratedStore.class},
                        (proxy, method, args) -> {
                            // Readback reads collected objects only after asking whether the
                            // store already holds them, so a failing store fails it too.
                            if (stage.equals("readback")
                                    && (method.getName().equals("readRange")
                                            || method.getName().equals("has"))) {
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("readback failure"));
                            }
                            if (stage.equals("reconcile-unavailable")
                                    && committed.get()
                                    && method.getName().equals("read")) {
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("storage unavailable"));
                            }
                            if (method.getName().equals("put")) {
                                for (var value : (List<?>) args[0]) {
                                    var put = (GeneratedTypes.Put) value;
                                    String json = new String(put.bytes(), StandardCharsets.UTF_8);
                                    boolean node =
                                            json.contains(
                                                    "vibeminer-generated-table-native-node v1");
                                    if (stage.equals("layout")
                                                    && json.contains(
                                                            "vibeminer-generated-table-layout v1")
                                            || stage.equals("alias")
                                                    && node
                                                    && !put.key().equals(finalNode)
                                            || stage.equals("final-before")
                                                    && put.key().equals(finalNode)) {
                                        return CompletableFuture.failedFuture(
                                                new IllegalStateException("injected " + stage));
                                    }
                                    if (put.key().equals(finalNode)
                                            && (stage.equals("final-after")
                                                    || stage.equals("reconcile-unavailable"))) {
                                        // The whole put commits, then its acknowledgement is lost.
                                        @SuppressWarnings("unchecked")
                                        var batch = (List<GeneratedTypes.Put>) args[0];
                                        memory.put(batch, com.vibeminer.generated.Cancellation.NONE)
                                                .join();
                                        committed.set(true);
                                        return CompletableFuture.failedFuture(
                                                new IllegalStateException("lost acknowledgement"));
                                    }
                                }
                            }
                            try {
                                return method.invoke(memory, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }
}
