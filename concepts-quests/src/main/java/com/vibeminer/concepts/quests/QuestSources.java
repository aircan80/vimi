package com.vibeminer.concepts.quests;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.vibeminer.concepts.quests.QuestCatalog.Input;
import com.vibeminer.concepts.quests.QuestCatalog.Observation;
import com.vibeminer.concepts.quests.QuestCatalog.Provider;
import com.vibeminer.concepts.quests.QuestCatalog.Source;
import com.vibeminer.eglink.EgLinkRepository;
import com.vibeminer.eglink.EgLinkTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.polestar.PolestarRepository;
import com.vibeminer.polestar.PolestarTypes;
import com.vibeminer.questie.QuestieRepository;
import com.vibeminer.questie.QuestieTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapters read prepared provider snapshots only; they never download or own borrowed repositories.
 */
public final class QuestSources {
    private static final int MAX_ROWS = 200_000;

    private QuestSources() {}

    public static Input blizzard(BuildTables tables, String product, String build, String locale) {
        return new Input(
                Provider.BLIZZARD,
                token -> {
                    var result = new ArrayList<Observation>();
                    for (String table : List.of("QuestV2", "QuestV2CliTask")) {
                        var ref = TableTypes.Ref.forName(table);
                        var schema =
                                tables.schema(ref, TableTypes.Priority.INTERACTIVE, token).join();
                        int title = column(schema, "QuestTitle_lang");
                        long after = -1;
                        String identity = null;
                        do {
                            token.check();
                            var page =
                                    tables.page(
                                                    new TableTypes.PageRequest(
                                                            ref, after, 500, List.of(), identity),
                                                    TableTypes.Priority.INTERACTIVE,
                                                    token)
                                            .join();
                            for (var wrapped : page.rows()) {
                                var row = wrapped.row();
                                result.add(
                                        new Observation(
                                                row.id(),
                                                text(row, title),
                                                null,
                                                null,
                                                new Source(
                                                        Provider.BLIZZARD,
                                                        table,
                                                        product,
                                                        build,
                                                        locale)));
                            }
                            bound(result.size());
                            if (page.nextAfter() == null) {
                                break;
                            }
                            if (page.nextAfter() <= after) {
                                throw new IllegalStateException("Non-advancing table cursor");
                            }
                            after = page.nextAfter();
                            identity = page.identity();
                        } while (true);
                    }
                    return result;
                });
    }

    private static int column(TableTypes.SchemaResult schema, String name) {
        if (schema.schema() == null
                || schema.columns() == null
                || schema.schema().columns().stream()
                        .noneMatch(
                                c ->
                                        c.name().equals(name)
                                                && c.verifiedName()
                                                && c.arrayLength() == 1
                                                && (c.kind()
                                                                == com.vibeminer.formats.definitions
                                                                        .TableSchema.ColumnKind
                                                                        .STRING
                                                        || c.kind()
                                                                == com.vibeminer.formats.definitions
                                                                        .TableSchema.ColumnKind
                                                                        .LOCSTRING))) {
            return -1;
        }
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(com.vibeminer.formats.db2.Db2Types.Row row, int column) {
        if (column < 0
                || column >= row.values().size()
                || row.textIssues() != null
                        && row.textIssues().stream().anyMatch(i -> i.columnIndex() == column)) {
            return null;
        }
        return row.values().get(column);
    }

    @SuppressWarnings("try")
    public static Input questie(
            QuestieRepository repository,
            String version,
            String product,
            String build,
            String locale) {
        return new Input(
                Provider.QUESTIE,
                token -> {
                    var result = new ArrayList<Observation>();
                    var cancel = new com.vibeminer.generated.Cancellation();
                    try (var registration = token.onCancel(cancel::cancel);
                            var handle = repository.openVersion(version, cancel).join()) {
                        for (int offset = 0; ; offset += 500) {
                            token.check();
                            var page =
                                    handle.search(
                                                    QuestieTypes.EntityKind.QUEST,
                                                    "",
                                                    locale,
                                                    offset,
                                                    500,
                                                    cancel)
                                            .join();
                            for (var row : page.entities()) {
                                result.add(
                                        from(
                                                row.id(),
                                                row.fields(),
                                                "requiredLevel",
                                                "questLevel",
                                                "name",
                                                new Source(
                                                        Provider.QUESTIE,
                                                        version,
                                                        handle.version().flavor().upstream(),
                                                        null,
                                                        locale)));
                            }
                            bound(result.size());
                            if (offset + page.entities().size() >= page.total()) {
                                break;
                            }
                            if (page.entities().isEmpty()) {
                                throw new IllegalStateException("Incomplete Questie page");
                            }
                        }
                    }
                    return result;
                });
    }

    @SuppressWarnings("try")
    public static Input polestar(
            PolestarRepository repository, PolestarTypes.Snapshot snapshot, String locale) {
        return new Input(
                Provider.POLESTAR,
                token -> {
                    var result = new ArrayList<Observation>();
                    var cancel = new com.vibeminer.network.Cancellation();
                    try (var registration = token.onCancel(cancel::cancel)) {
                        for (int offset = 0; ; offset += 500) {
                            token.check();
                            var page =
                                    repository
                                            .search(
                                                    snapshot.id(),
                                                    PolestarTypes.Table.QUEST,
                                                    "",
                                                    offset,
                                                    500,
                                                    cancel)
                                            .join();
                            for (var row : page.records()) {
                                result.add(
                                        from(
                                                Long.parseLong(row.key()),
                                                row.fields(),
                                                "min_level",
                                                "level",
                                                "title",
                                                new Source(
                                                        Provider.POLESTAR,
                                                        snapshot.id(),
                                                        "Forever",
                                                        snapshot.catalog().clientBuild(),
                                                        "enUS")));
                            }
                            bound(result.size());
                            if (offset + page.records().size() >= page.total()) {
                                break;
                            }
                            if (page.records().isEmpty()) {
                                throw new IllegalStateException("Incomplete Polestar page");
                            }
                        }
                    }
                    return result;
                });
    }

    @SuppressWarnings("try")
    public static Input egLink(
            EgLinkRepository repository, EgLinkTypes.Snapshot snapshot, String locale) {
        return new Input(
                Provider.EG_LINK,
                token -> {
                    var result = new ArrayList<Observation>();
                    var cancel = new com.vibeminer.network.Cancellation();
                    try (var registration = token.onCancel(cancel::cancel)) {
                        String dataset = "quests." + locale;
                        if (snapshot.build().datasets().stream()
                                .noneMatch(d -> d.name().equals(dataset))) {
                            return result;
                        }
                        String collection = "quests";
                        {
                            for (int offset = 0; ; offset += 200) {
                                token.check();
                                var page =
                                        repository
                                                .search(
                                                        snapshot.id(),
                                                        dataset,
                                                        collection,
                                                        "",
                                                        offset,
                                                        200,
                                                        cancel)
                                                .join();
                                for (var row : page.records()) {
                                    var fields = egFields(row.json());
                                    result.add(
                                            from(
                                                    Long.parseLong(row.id()),
                                                    fields,
                                                    "minLevel",
                                                    "level",
                                                    "title",
                                                    new Source(
                                                            Provider.EG_LINK,
                                                            snapshot.id() + "/" + dataset,
                                                            snapshot.build().product(),
                                                            snapshot.build().version(),
                                                            locale)));
                                }
                                bound(result.size());
                                if (!page.more()) {
                                    break;
                                }
                            }
                        }
                    }
                    return result;
                });
    }

    static Observation from(
            long id,
            Map<String, Object> fields,
            String required,
            String level,
            String title,
            Source source) {
        return new Observation(
                id,
                string(fields.get(title)),
                integer(fields.get(required)),
                integer(fields.get(level)),
                source);
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue()
                || n.intValue() < -1
                || n.intValue() > 1000) {
            return null;
        }
        return n.intValue();
    }

    static Map<String, Object> egFields(String json) throws java.io.IOException {
        var result = new java.util.HashMap<String, Object>();
        try (var parser = new JsonFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new java.io.IOException("Expected quest object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                var value = parser.nextToken();
                if (value == JsonToken.VALUE_STRING) {
                    result.put(name, parser.getText());
                } else if (value == JsonToken.VALUE_NUMBER_INT) {
                    result.put(name, parser.getLongValue());
                } else {
                    parser.skipChildren();
                }
            }
        }
        return result;
    }

    private static void bound(int rows) {
        if (rows > MAX_ROWS) {
            throw new IllegalStateException("quest source exceeds supported bound");
        }
    }
}
