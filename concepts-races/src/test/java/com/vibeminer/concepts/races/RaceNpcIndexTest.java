package com.vibeminer.concepts.races;

import static com.vibeminer.concepts.races.RaceAppearancePlanTest.BUILD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.HotfixViewInfo;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The persistent NPC appearance index over the Orc NPC fixture (NPC 50, bake 4242 over model 1234
 * with skin 900): a hit skips the resolve, omissions and plan; anything changed is a miss.
 */
final class RaceNpcIndexTest {
    private static BuildTables raw() {
        return new MemoryTables()
                .add(BUILD, "enUS", RaceNpcAppearanceTest.npcFixture())
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    /** The NPC's files; {@code bakeKey} is the bake's content key character. */
    private static AssetFilesFixture files(char bakeKey) throws Exception {
        return new AssetFilesFixture(BUILD, "enUS")
                .add(1234, "1".repeat(32), RaceNpcAppearanceTest.withWeight(false))
                .add(900, "2".repeat(32), RaceNpcAppearanceTest.golden("authored-274.skin"))
                .add(4242, String.valueOf(bakeKey).repeat(32), RaceNpcAppearanceTest.bake());
    }

    /**
     * {@code raw} with row reads ({@code get}, and pages of more than the one row a fingerprint
     * reads) counted in {@code rows}, {@code change} applied to every schema answer, and {@code
     * hotfixes} as the hotfix views of the tables they name.
     */
    private static BuildTables observed(
            BuildTables raw,
            AtomicInteger rows,
            UnaryOperator<TableTypes.SchemaResult> change,
            Map<String, HotfixViewInfo> hotfixes) {
        return (BuildTables)
                java.lang.reflect.Proxy.newProxyInstance(
                        RaceNpcIndexTest.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("get")
                                    || method.getName().equals("page")
                                            && ((TableTypes.PageRequest) args[0]).limit() > 1) {
                                rows.incrementAndGet();
                            }
                            if (method.getName().equals("hotfixInfo")) {
                                var info = hotfixes.get(((TableTypes.Ref) args[0]).name());
                                if (info != null) {
                                    return CompletableFuture.completedFuture(Optional.of(info));
                                }
                            }
                            try {
                                Object answer = method.invoke(raw, args);
                                if (method.getName().equals("schema")) {
                                    return ((CompletableFuture<?>) answer)
                                            .thenApply(
                                                    schema ->
                                                            change.apply(
                                                                    (TableTypes.SchemaResult)
                                                                            schema));
                                }
                                return answer;
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    private static BuildTables observed(BuildTables raw, AtomicInteger rows) {
        return observed(raw, rows, schema -> schema, Map.of());
    }

    /** {@code schema} with the entry's content key replaced for {@code table}. */
    private static TableTypes.SchemaResult contentKey(
            TableTypes.SchemaResult schema, String table, String contentKey) {
        var entry = schema.table();
        if (table != null && !table.equals(entry.name())) {
            return schema;
        }
        return new TableTypes.SchemaResult(
                new TableTypes.Entry(
                        entry.fileDataId(),
                        entry.name(),
                        entry.state(),
                        entry.checked(),
                        entry.reason(),
                        entry.counts(),
                        contentKey),
                schema.columns(),
                schema.schema());
    }

    /** One handle's answer for NPC 50. */
    private static RaceNpcs.Appearance ask(
            BuildTables tables, BuildFiles files, GeneratedStore generated) {
        var service = new RacesService(tables, files, generated);
        try {
            return service.npcAppearance(50, Cancellation.NONE).join();
        } finally {
            service.closeAsync().join();
        }
    }

    private static RaceNpcIndex index(BuildTables tables, BuildFiles files) {
        return new RaceNpcIndex(tables, files, new MemoryGeneratedStore());
    }

    private static boolean present(GeneratedStore generated, GeneratedKey key) {
        return generated.read(key).join().state() == GeneratedTypes.ReadState.PRESENT;
    }

    /** What the World draws and states from an answer: everything but the restored flag. */
    private static List<Object> drawn(RaceNpcs.Appearance answer) {
        var result = answer.result();
        var appearance = result.appearance();
        return List.of(
                java.util.Arrays.toString(appearance.mesh().positions()),
                java.util.Arrays.toString(appearance.mesh().indices()),
                appearance.mesh().parts(),
                java.util.Arrays.toString(appearance.uv()),
                appearance.surfaces(),
                appearance.textures().stream()
                        .map(texture -> java.util.Arrays.toString(texture.argb()))
                        .toList(),
                result.state(),
                result.visibleParts(),
                result.items(),
                result.issues(),
                String.valueOf(result.message()),
                answer.omittedItems(),
                answer.omittedAttachments());
    }

    @Test
    void aCompleteAppearanceIsIndexedAndTheNextHandleRestoresItWithoutResolving() throws Exception {
        var raw = raw();
        var files = files('3');
        var generated = new MemoryGeneratedStore();
        var first = ask(observed(raw, new AtomicInteger()), files, generated);
        assertNotNull(first.result(), String.valueOf(first));
        assertFalse(first.result().restored());
        assertTrue(present(generated, index(raw, files).key(50)), "written once complete");

        var rows = new AtomicInteger();
        var hit = ask(observed(raw, rows), files, generated);
        assertEquals(0, rows.get(), "a hit reads no rows: no resolve, omissions or plan");
        assertTrue(hit.result().restored());
        assertEquals(drawn(first), drawn(hit));

        // The same answer as a warm store without the index, which resolves and restores.
        var plain = new MemoryGeneratedStore();
        ask(raw, files, plain);
        plain.delete(List.of(index(raw, files).key(50))).join();
        var warmRows = new AtomicInteger();
        var warm = ask(observed(raw, warmRows), files, plain);
        assertTrue(warmRows.get() > 0, "without an entry the NPC is resolved");
        assertTrue(warm.result().restored());
        assertEquals(drawn(warm), drawn(hit));
        // That warm pass wrote the entry, and a hit then answers the same.
        assertEquals(drawn(warm), drawn(ask(raw, files, plain)));
    }

    /**
     * The fixture with NPC 50's item slot rows changed: the boots (display 600, slot 6) become a
     * chest (slot 3), and the table's content key changes with them.
     */
    private static BuildTables changedSlots() {
        var seed = new java.util.ArrayList<>(RaceNpcAppearanceTest.npcFixture());
        var slots =
                seed.stream()
                        .filter(t -> t.entry().name().equals("NPCModelItemSlotDisplayInfo"))
                        .findFirst()
                        .orElseThrow();
        seed.remove(slots);
        var entry = slots.entry();
        seed.add(
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                entry.fileDataId(),
                                entry.name(),
                                entry.state(),
                                entry.checked(),
                                entry.reason(),
                                entry.counts(),
                                "f".repeat(32)),
                        slots.columns(),
                        List.of(RaceNpcsTest.row(1, 600, 3, 50)),
                        slots.schema(),
                        slots.protectedIds(),
                        slots.generated()));
        return new MemoryTables()
                .add(BUILD, "enUS", seed)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void aChangedTableIsAMissAndTheEntryIsRewritten() throws Exception {
        var raw = raw();
        var files = files('3');
        var generated = new MemoryGeneratedStore();
        var key = index(raw, files).key(50);
        var first = ask(raw, files, generated);
        byte[] before = generated.read(key).join().bytes();
        var rows = new AtomicInteger();
        var changed = changedSlots();
        var miss = ask(observed(changed, rows), files, generated);
        assertTrue(rows.get() > 0, "resolved again");
        assertNotEquals(first.result().items(), miss.result().items(), "the new slot rows");
        byte[] after = generated.read(key).join().bytes();
        assertFalse(java.util.Arrays.equals(before, after), "rewritten for the changed table");
        // The rewritten entry describes the changed table: a hit there, a miss on the first one.
        var hit = new AtomicInteger();
        assertEquals(drawn(miss), drawn(ask(observed(changed, hit), files, generated)));
        assertEquals(0, hit.get());
        var back = new AtomicInteger();
        assertEquals(drawn(first), drawn(ask(observed(raw, back), files, generated)));
        assertTrue(back.get() > 0);
        // Only the entry changed: a table's state or content key alone is a miss too.
        var partial = new AtomicInteger();
        ask(
                observed(
                        raw,
                        partial,
                        schema -> contentKey(schema, "ChrRaces", "e".repeat(32)),
                        Map.of()),
                files,
                generated);
        assertTrue(partial.get() > 0);
    }

    @Test
    void aChangedOrRefusedHotfixViewIsAMiss() throws Exception {
        var raw = raw();
        var files = files('3');
        var generated = new MemoryGeneratedStore();
        var view = new HotfixViewInfo("input-1", "view-1", "policy", 1, 2, 1, 0, 0, 0, 0, 0, null);
        ask(
                observed(raw, new AtomicInteger(), s -> s, Map.of("ItemDisplayInfo", view)),
                files,
                generated);
        var same = new AtomicInteger();
        ask(observed(raw, same, s -> s, Map.of("ItemDisplayInfo", view)), files, generated);
        assertEquals(0, same.get(), "the same hotfix view hits");
        // A new cache input changes the view.
        var changed =
                new HotfixViewInfo("input-2", "view-2", "policy", 1, 2, 1, 0, 0, 0, 0, 0, null);
        var rows = new AtomicInteger();
        ask(observed(raw, rows, s -> s, Map.of("ItemDisplayInfo", changed)), files, generated);
        assertTrue(rows.get() > 0, "a changed hotfix view is a miss");
        // The overlay refused for its budget: the rows are base rows, so a miss too.
        var refused =
                new HotfixViewInfo(
                        "input-2",
                        "view-2",
                        "policy",
                        1,
                        2,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        HotfixViewInfo.TABLE_BUDGET);
        var base = new AtomicInteger();
        ask(observed(raw, base, s -> s, Map.of("ItemDisplayInfo", refused)), files, generated);
        assertTrue(base.get() > 0, "a refused hotfix view is a miss");
        // No hotfix view at all: a miss as well.
        var none = new AtomicInteger();
        ask(observed(raw, none), files, generated);
        assertTrue(none.get() > 0);
    }

    @Test
    void aSourceFileWithANewContentKeyIsAMiss() throws Exception {
        var raw = raw();
        var generated = new MemoryGeneratedStore();
        ask(raw, files('3'), generated);
        var rows = new AtomicInteger();
        var miss = ask(observed(raw, rows), files('4'), generated);
        assertTrue(rows.get() > 0, "the bake changed: resolved and composed again");
        assertFalse(miss.result().restored(), "a new composite for the new bake");
        // The model gone from the build: a miss, then the stated refusal.
        var noModel =
                new AssetFilesFixture(BUILD, "enUS")
                        .add(900, "2".repeat(32), RaceNpcAppearanceTest.golden("authored-274.skin"))
                        .add(4242, "4".repeat(32), RaceNpcAppearanceTest.bake());
        var refused = ask(raw, noModel, generated);
        assertNotNull(refused.refusal(), String.valueOf(refused));
    }

    @Test
    void anEntryWhoseCompositeIsGoneOrDamagedFallsBackToTheFullPath() throws Exception {
        var raw = raw();
        var files = files('3');
        var generated = new MemoryGeneratedStore();
        var first = ask(raw, files, generated);
        var index = index(raw, files);
        var key = index.key(50);
        var entry = RaceNpcIndex.decode(generated.read(key).join().bytes(), index.scope(50));
        assertNotNull(entry);
        generated.delete(List.of(entry.composite())).join();
        var rows = new AtomicInteger();
        var again = ask(observed(raw, rows), files, generated);
        assertTrue(rows.get() > 0);
        assertFalse(again.result().restored(), "composed again");
        assertEquals(drawn(first), drawn(again));
        // A damaged entry is a miss and is replaced.
        generated.delete(List.of(key)).join();
        generated.put(List.of(new GeneratedTypes.Put(key, new byte[] {1, 2, 3}))).join();
        var damaged = new AtomicInteger();
        assertNotNull(ask(observed(raw, damaged), files, generated).result());
        assertTrue(damaged.get() > 0);
        assertNotNull(RaceNpcIndex.decode(generated.read(key).join().bytes(), index.scope(50)));
    }

    @Test
    void anAppearanceShownWithSomethingUnreadIsNeverWritten() throws Exception {
        var raw = raw();
        // The bake needs the network: asked for again, nothing written.
        var later = files('3');
        later.failOpen(
                4242,
                new com.vibeminer.files.FileTypes.FileOpen(
                        com.vibeminer.files.FileTypes.FileReadState.NEEDS_NETWORK,
                        null,
                        "FILES_NEEDS_NETWORK",
                        "not stored locally",
                        null));
        var generated = new MemoryGeneratedStore();
        var key = index(raw, later).key(50);
        var asked = ask(raw, later, generated);
        assertTrue(asked.retry(), String.valueOf(asked));
        assertFalse(present(generated, key));
        // An item display row that cannot be read now: asked again, nothing written.
        var unknown =
                RaceNpcAppearanceTest.answer(
                        raw, "ItemDisplayInfo", TableTypes.RowOutcome.UNKNOWN, null);
        var unreadable = ask(unknown, files('3'), generated);
        assertTrue(unreadable.retry(), String.valueOf(unreadable));
        assertFalse(present(generated, key));
    }

    @Test
    void entriesRoundTripAndAnythingElseDecodesToNothing() {
        var entry =
                new RaceNpcIndex.Entry(
                        GeneratedKey.hash(new byte[] {1}),
                        1234,
                        Set.of(0, 401, 20_500),
                        List.of(new RaceAppearanceTypes.Item(7, 8, 9)),
                        List.of("a fact", "another, with ünïcode"),
                        2,
                        1,
                        Map.of(1234L, "1".repeat(32), 77L, "!NOT_FOUND"),
                        List.of(
                                TableTypes.Ref.forName("ItemDisplayInfo"),
                                TableTypes.Ref.id(1_234_567)),
                        new byte[32]);
        byte[] bytes = RaceNpcIndex.encode(entry, "scope");
        var back = RaceNpcIndex.decode(bytes, "scope");
        assertNotNull(back);
        assertEquals(entry.composite(), back.composite());
        assertEquals(1234, back.modelFileDataId());
        assertEquals(entry.visible(), back.visible());
        assertEquals(entry.items(), back.items());
        assertEquals(entry.issues(), back.issues());
        assertEquals(entry.sources(), back.sources());
        assertEquals(entry.tables(), back.tables());
        assertEquals(List.of(2, 1), List.of(back.omittedItems(), back.omittedAttachments()));
        assertArrayEquals(entry.digest(), back.digest());
        assertNull(RaceNpcIndex.decode(bytes, "another scope"), "written for another key");
        for (int cut = 0; cut < bytes.length; cut++) {
            assertNull(RaceNpcIndex.decode(java.util.Arrays.copyOf(bytes, cut), "scope"));
        }
        assertNull(
                RaceNpcIndex.decode(java.util.Arrays.copyOf(bytes, bytes.length + 1), "scope"),
                "trailing bytes");
        byte[] huge = bytes.clone();
        // The visible count: past the magic, the scope, the key, the model and two counts.
        huge[4 + 4 + "scope".length() + 16 + 8 + 4 + 4] = 0x7f;
        assertNull(RaceNpcIndex.decode(huge, "scope"), "a list past its bound");
    }

    @Test
    void aTableThatChangedWithinTheHandleWritesNothing() throws Exception {
        var raw = raw();
        var files = files('3');
        var generated = new MemoryGeneratedStore();
        var composite = GeneratedKey.hash(new byte[] {9});
        generated.put(List.of(new GeneratedTypes.Put(composite, new byte[] {1}))).join();
        String[] key = {"c".repeat(32)};
        var tables =
                observed(
                        raw,
                        new AtomicInteger(),
                        schema -> contentKey(schema, null, key[0]),
                        Map.of());
        var index = new RaceNpcIndex(tables, files, generated);
        var recorded = index.recording(tables);
        recorded.schema(
                        TableTypes.Ref.forName("ChrRaces"),
                        TableTypes.Priority.INTERACTIVE,
                        Cancellation.NONE)
                .join();
        assertEquals(Set.of(TableTypes.Ref.forName("ChrRaces")), index.touched());
        var result =
                new RaceAppearanceTypes.Result(
                        RaceTypes.State.PARTIAL,
                        new com.vibeminer.rendering.models.MeshAppearance(
                                new com.vibeminer.rendering.models.PreviewMesh(
                                        new float[9],
                                        new int[] {0, 1, 2},
                                        List.of(
                                                new com.vibeminer.rendering.models.PreviewMesh.Part(
                                                        0, 0, 3))),
                                new float[6],
                                new float[9],
                                List.of(),
                                List.of()),
                        Set.of(0),
                        List.of(),
                        List.of(),
                        false,
                        null);
        assertTrue(write(index, composite, result));
        key[0] = "d".repeat(32);
        assertFalse(write(index, composite, result), "described differently than before");
        assertTrue(write(index, composite, result), "described afresh after the change");
        // A composite not in the store is never indexed.
        assertFalse(write(index, GeneratedKey.hash(new byte[] {8}), result));
        // A table call that may read tables it does not name: nothing more is written.
        recorded.follow(
                        new TableTypes.FollowRequest(TableTypes.Ref.forName("ChrRaces"), 2, "x"),
                        TableTypes.Priority.INTERACTIVE,
                        Cancellation.NONE)
                .exceptionally(failure -> null)
                .join();
        assertTrue(index.untracked());
        assertFalse(write(index, composite, result));
    }

    private static boolean write(
            RaceNpcIndex index, GeneratedKey composite, RaceAppearanceTypes.Result result) {
        return index.write(
                50,
                composite,
                1234,
                result,
                0,
                0,
                Map.of(1234L, "1".repeat(32)),
                Set.of(),
                Cancellation.NONE);
    }

    @Test
    void theCodeDigestNamesTheCompiledClasses() {
        assertNotNull(RaceNpcIndex.CODE, "the NPC path's class files are readable");
        assertEquals(RaceNpcIndex.CODE, RaceNpcIndex.code(RaceNpcIndex.CODE_CLASSES));
        assertNotEquals(
                RaceNpcIndex.CODE,
                RaceNpcIndex.code(List.of(RaceNpcIndex.class)),
                "other code, another digest");
    }
}
