package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.hotfix.HotfixRowDecoder;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.BuildTactKeys;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.TablesContract;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Delivered client-cache keys join the build keys; failing to read them only means fewer keys. */
public final class DeliveredKeysTest {
    private DeliveredKeysTest() {}

    public static void main(String[] args) {
        var found = buildKeys();
        byte[] fresh = new byte[16];
        fresh[1] = 3;
        var block = List.of(new HotfixRowDecoder.OptionalKey(10, fresh));

        var joined = NativePreparationWork.withDeliveredKeys(
                tables(() -> CompletableFuture.completedFuture(block)), found, Cancellation.NONE);
        check(joined.count() == 2 && joined.find(10) != null, "a delivered key joins the build keys");

        var failed = NativePreparationWork.withDeliveredKeys(
                tables(() -> CompletableFuture.failedFuture(new IllegalStateException("authored"))),
                found, Cancellation.NONE);
        check(failed == found, "a failed read keeps the build keys as they are");

        var refused = NativePreparationWork.withDeliveredKeys(
                tables(() -> {
                    throw new IllegalStateException("authored refusal");
                }), found, Cancellation.NONE);
        check(refused == found, "a refused read keeps the build keys as they are");

        var cancelled = new Cancellation();
        cancelled.cancel();
        boolean propagated = false;
        try {
            NativePreparationWork.withDeliveredKeys(
                    tables(() -> CompletableFuture.failedFuture(new IllegalStateException("authored"))),
                    found, cancelled);
        } catch (java.util.concurrent.CancellationException expected) {
            propagated = true;
        }
        check(propagated, "cancellation is not taken for a failed read");
        System.out.println("DeliveredKeysTest PASS");
    }

    private static BuildTables tables(
            Supplier<CompletableFuture<List<HotfixRowDecoder.OptionalKey>>> delivered) {
        return (BuildTables) Proxy.newProxyInstance(BuildTables.class.getClassLoader(),
                new Class<?>[] {BuildTables.class}, (proxy, method, values) -> {
                    if (method.getName().equals("deliveredKeys")) return delivered.get();
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BuildTactKeys.KeySet buildKeys() {
        var memory = new MemoryTables().add(TablesContract.BUILD, "enUS", List.of(
                table(BuildTactKeys.TACT_KEY_FILE_DATA_ID, "TactKey", "Key", 16, 1),
                table(BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID, "TactKeyLookup", "TACTID", 8, 9)));
        try (var handle = memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var keys = BuildTactKeys.load(handle, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            check(keys.count() == 1, "fixture has one build key");
            return keys;
        }
    }

    private static MemoryTables.FakeTable table(long id, String name, String column, int size, int first) {
        var columns = new ArrayList<Db2Types.Column>();
        var values = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            columns.add(new Db2Types.Column(column + "[" + i + "]", "integer", false, null, null));
            values.add(i == 0 ? Integer.toString(first) : "0");
        }
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(id, name, TableTypes.State.READY, true, null, null, "a".repeat(32)),
                columns, List.of(new Db2Types.Row(7, false, null, values, null)), null, Map.of(), true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
