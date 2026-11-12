package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The appearance plan's filtered relationships: the relation index answers as the page scan. */
final class RaceAppearancePlanQueryTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    /** Material resources 5 (three rows, out of ID order), 7 (two) and 9 (one). */
    private static MemoryTables.FakeTable textures(TableTypes.State state) {
        var table =
                RaceNpcsTest.table(
                        900,
                        "TextureFileData",
                        List.of(
                                RaceNpcsTest.number("UsageType"),
                                RaceNpcsTest.number("MaterialResourcesID")),
                        List.of(
                                RaceNpcsTest.row(10, 0, 5),
                                RaceNpcsTest.row(11, 1, 7),
                                RaceNpcsTest.row(12, 0, 7),
                                RaceNpcsTest.row(13, 2, 5),
                                RaceNpcsTest.row(14, 0, 9),
                                RaceNpcsTest.row(15, 0, 5)));
        var entry = table.entry();
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        entry.fileDataId(),
                        entry.name(),
                        state,
                        entry.checked(),
                        entry.reason(),
                        entry.counts(),
                        entry.contentKey()),
                table.columns(),
                table.rows(),
                table.schema(),
                table.protectedIds(),
                table.generated());
    }

    private static List<Long> ids(List<Db2Types.Row> rows) {
        return rows.stream().map(Db2Types.Row::id).toList();
    }

    private static List<Long> scan(BuildTables tables, long value) {
        var page =
                tables.page(
                                new TableTypes.PageRequest(
                                        TableTypes.Ref.forName("TextureFileData"),
                                        -1,
                                        RaceAppearancePlan.QUERY_ROWS,
                                        List.of(
                                                new TableTypes.Filter(
                                                        "MaterialResourcesID",
                                                        TableTypes.FilterOp.EQUALS,
                                                        Long.toString(value),
                                                        null)),
                                        null),
                                TableTypes.Priority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        return page.rows().stream().map(row -> row.row().id()).toList();
    }

    private interface Body {
        void run(BuildTables tables, RacesService service, RaceAppearancePlan plan)
                throws Exception;
    }

    private static void with(TableTypes.State state, Body body) throws Exception {
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", List.of(textures(state)))
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            body.run(
                    tables,
                    service,
                    new RaceAppearancePlan(
                            service, new RaceMedia(service, tables, null, null), tables));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    @Test
    void theIndexAnswersAsTheScanForEveryKeyAndInTheSameOrder() throws Exception {
        with(
                TableTypes.State.READY,
                (tables, service, plan) -> {
                    var data =
                            service.schema("TextureFileData", Cancellation.NONE, new ArrayList<>());
                    for (long key : new long[] {5, 7, 9, 1}) {
                        assertEquals(
                                scan(tables, key),
                                ids(
                                        plan.query(
                                                data,
                                                "MaterialResourcesID",
                                                key,
                                                Cancellation.NONE)),
                                "key " + key);
                    }
                    assertEquals(List.of(10L, 13L, 15L), scan(tables, 5), "several rows, in order");
                    assertTrue(service.relations.builds() > 0, "the index answered");
                });
    }

    @Test
    void aPartialTableIsAnsweredByTheScanWithItsReadableRows() throws Exception {
        with(
                TableTypes.State.PARTIAL,
                (tables, service, plan) -> {
                    var data =
                            service.schema("TextureFileData", Cancellation.NONE, new ArrayList<>());
                    assertEquals(
                            List.of(10L, 13L, 15L),
                            ids(plan.query(data, "MaterialResourcesID", 5, Cancellation.NONE)));
                });
    }

    private static RelationIndexes.Lookup lookup(
            RelationIndexes.State state, TableTypes.State table, int matches) {
        var entry =
                new TableTypes.Entry(
                        900, "TextureFileData", table, true, null, null, "c".repeat(32));
        return new RelationIndexes.Lookup(
                state,
                List.of(new TableTypes.Row(0, RaceNpcsTest.row(10, 0, 5))),
                matches,
                entry,
                null,
                null,
                "identity");
    }

    @Test
    void lookupsTheIndexCannotProveFallBackOrRefuseAsThePageDoes() throws Exception {
        with(
                TableTypes.State.READY,
                (tables, service, plan) -> {
                    var data =
                            service.schema("TextureFileData", Cancellation.NONE, new ArrayList<>());
                    assertEquals(
                            List.of(10L),
                            ids(
                                    RaceAppearancePlan.indexed(
                                            lookup(
                                                    RelationIndexes.State.OK,
                                                    TableTypes.State.READY,
                                                    1),
                                            data)));
                    assertNull(RaceAppearancePlan.indexed(null, data), "no lookup: the page");
                    for (var state :
                            List.of(
                                    RelationIndexes.State.UNSUPPORTED,
                                    RelationIndexes.State.INDEX_LIMIT)) {
                        assertNull(
                                RaceAppearancePlan.indexed(
                                        lookup(state, TableTypes.State.READY, 1), data),
                                state + ": the page");
                    }
                    assertNull(
                            RaceAppearancePlan.indexed(
                                    lookup(RelationIndexes.State.OK, TableTypes.State.PARTIAL, 1),
                                    data),
                            "a partial table: the page keeps its readable rows");
                    for (var state :
                            List.of(
                                    RelationIndexes.State.STALE,
                                    RelationIndexes.State.NOT_READABLE)) {
                        var unreadable =
                                assertThrows(
                                        RacesService.Unreadable.class,
                                        () ->
                                                RaceAppearancePlan.indexed(
                                                        lookup(state, TableTypes.State.READY, 1),
                                                        data));
                        assertTrue(unreadable.getMessage().contains("filtered relationship"));
                    }
                    var over =
                            assertThrows(
                                    RacesService.Projection.class,
                                    () ->
                                            RaceAppearancePlan.indexed(
                                                    lookup(
                                                            RelationIndexes.State.OK,
                                                            TableTypes.State.READY,
                                                            RaceAppearancePlan.QUERY_ROWS + 1),
                                                    data));
                    assertTrue(over.getMessage().contains("exceeds bounds"), over.getMessage());
                });
    }
}
