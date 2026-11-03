package com.vibeminer.concepts.npcs;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.vibeminer.concepts.npcs.NpcCatalog.Input;
import com.vibeminer.concepts.npcs.NpcCatalog.Levels;
import com.vibeminer.concepts.npcs.NpcCatalog.Observation;
import com.vibeminer.concepts.npcs.NpcCatalog.Provider;
import com.vibeminer.concepts.npcs.NpcCatalog.Source;
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
public final class NpcSources {
    private static final int MAX_ROWS = 200_000;

    private NpcSources() {}

    public static Input blizzard(BuildTables tables, String product, String build, String locale) {
        return new Input(
                Provider.BLIZZARD,
                token -> {
                    var result = new ArrayList<Observation>();
                    var ref = TableTypes.Ref.forName("Creature");
                    var schema = tables.schema(ref, TableTypes.Priority.INTERACTIVE, token).join();
                    int name = column(schema, "Name_lang"), title = column(schema, "Title_lang");
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
                                            text(row, name),
                                            text(row, title),
                                            null,
                                            new Source(
                                                    Provider.BLIZZARD,
                                                    "Creature",
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
                                                    QuestieTypes.EntityKind.NPC,
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
                                                "subName",
                                                "minLevel",
                                                "maxLevel",
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
                                                    PolestarTypes.Table.NPC,
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
                                                "subname",
                                                "level_min",
                                                "level_max",
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
                        for (String dataset : List.of("creature_details." + locale, "npcs")) {
                            if (snapshot.build().datasets().stream()
                                    .noneMatch(d -> d.name().equals(dataset))) {
                                continue;
                            }
                            String collection =
                                    dataset.startsWith("creature_details.") ? "creatures" : "npcs";
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
                                                    "title",
                                                    "minLevel",
                                                    "maxLevel",
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
            String title,
            String minimum,
            String maximum,
            Source source) {
        Integer min = integer(fields.get(minimum)), max = integer(fields.get(maximum));
        Levels levels =
                min != null && max != null && min >= 0 && max >= min && max <= 1000
                        ? new Levels(min, max)
                        : null;
        return new Observation(
                id, string(fields.get("name")), string(fields.get(title)), levels, source);
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue()) {
            return null;
        }
        return n.intValue();
    }

    static Map<String, Object> egFields(String json) throws java.io.IOException {
        var result = new java.util.HashMap<String, Object>();
        try (var parser = new JsonFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new java.io.IOException("Expected NPC object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                var value = parser.nextToken();
                if (value == JsonToken.VALUE_STRING) {
                    result.put(name, parser.getText());
                } else if (name.equals("levels") && value == JsonToken.START_ARRAY) {
                    Integer min = null, max = null;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                            int level = parser.getIntValue();
                            min = min == null ? level : Math.min(min, level);
                            max = max == null ? level : Math.max(max, level);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    if (min != null) {
                        result.put("minLevel", min);
                        result.put("maxLevel", max);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return result;
    }

    private static void bound(int rows) {
        if (rows > MAX_ROWS) {
            throw new IllegalStateException("NPC source exceeds supported bound");
        }
    }
}
