package com.vibeminer.questie.data;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.generated.DerivedDatabase;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.Progress;
import com.vibeminer.questie.QuestieTypes.Stage;
import com.vibeminer.questie.data.QuestieDataTypes.Relation;
import com.vibeminer.questie.internal.Json;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

final class Importer {
    private static final List<String> LOCALES =
            List.of("deDE", "esES", "esMX", "frFR", "koKR", "ptBR", "ruRU", "zhCN", "zhTW");
    private final BakedArchive archive;
    private final DerivedDatabase.Session db;
    private final Cancellation token;
    private final Consumer<Progress> progress;
    private final Map<EntityKind, List<Object>> ids = new EnumMap<>(EntityKind.class);
    final List<String> locales = new ArrayList<>(List.of("enUS"));
    final List<String> diagnostics = new ArrayList<>();
    long entityCount;

    Importer(
            BakedArchive archive,
            DerivedDatabase.Session db,
            Cancellation token,
            Consumer<Progress> progress) {
        this.archive = archive;
        this.db = db;
        this.token = token;
        this.progress = progress;
    }

    void run() {
        long total = 0;
        for (var kind : EntityKind.values()) {
            var values = Json.array(archive.decode("X-" + kind.upstream() + "-IDS", true, token));
            long previous = 0;
            for (Object value : values) {
                long id = id(value);
                if (id <= previous) {
                    throw new IllegalArgumentException("Unsorted/duplicate entity ID");
                }
                previous = id;
            }
            ids.put(kind, values);
            total += values.size();
            if (total > 2_000_000) {
                throw new IllegalArgumentException("Snapshot entity limit");
            }
            if (!archive.schemas.get(kind).unknownNames.isEmpty()) {
                diagnostics.add("Preserved extended schema fields for " + kind);
            }
        }
        progress.accept(
                new Progress(
                        Stage.IMPORTING,
                        0L,
                        total,
                        "entities",
                        "Decoding corrected static entities"));
        for (var kind : EntityKind.values()) {
            for (Object value : ids.get(kind)) {
                token.check();
                long id = id(value);
                importEntity(kind, id);
                entityCount++;
                if (entityCount % 500 == 0) {
                    progress.accept(
                            new Progress(
                                    Stage.IMPORTING,
                                    entityCount,
                                    total,
                                    "entities",
                                    kind.upstream()));
                }
            }
        }
        progress.accept(
                new Progress(
                        Stage.INDEXING,
                        0L,
                        null,
                        "translations",
                        "Decoding stored locale columns"));
        for (String locale : LOCALES) {
            int found = 0;
            for (var kind : EntityKind.values()) {
                Object block =
                        archive.decode("X-l10n-" + locale + "-" + kind.upstream(), true, token);
                if (block != null) {
                    found++;
                    importLocale(locale, kind, block);
                }
            }
            if (found > 0) {
                locales.add(locale);
            }
            if (found != 0 && found != 4) {
                diagnostics.add(
                        "Partial base translations for "
                                + locale
                                + ": "
                                + found
                                + "/4 entity blocks");
            }
        }
        diagnostics.add(
                "Static corrected baked base only; runtime character/season corrections and consumer policies are unevaluated. No drop rates or original per-field correction authorship are inferred.");
        diagnostics.add(
                "Source package retained separately; nested CBOR integer map keys use decimal string keys in JSON.");
        if (locales.size() == 1) {
            diagnostics.add("No generated base translations; locale reads fall back to English.");
        }
        for (var entry : archive.metadata.entrySet()) {
            if (entry.getKey().startsWith("x-")
                    && !archive.usedKeys.contains(entry.getKey())
                    && !List.of(
                                    "x-contract-version",
                                    "x-flavor",
                                    "x-mode",
                                    "x-build-commit",
                                    "x-build-time",
                                    "x-l10n-version")
                            .contains(entry.getKey())) {
                db.execute(
                        "INSERT INTO unknown_metadata(key,value) VALUES(?,?)",
                        entry.getKey(),
                        entry.getValue());
            }
        }
        long unknownMetadata =
                ((Number) db.query("SELECT count(*) FROM unknown_metadata").getFirst()[0])
                        .longValue();
        if (unknownMetadata > 0) {
            diagnostics.add(
                    unknownMetadata
                            + " unrecognized metadata entries retained verbatim in the projection.");
        }
        progress.accept(
                new Progress(
                        Stage.VALIDATING,
                        entityCount,
                        entityCount,
                        "entities",
                        "Checking stored references and coverage"));
        long missing =
                ((Number)
                                db.query(
                                                "SELECT count(*) FROM entity_references r LEFT JOIN entities e ON e.kind=r.to_kind AND e.id=r.to_id WHERE e.id IS NULL")
                                        .getFirst()[0])
                        .longValue();
        if (missing > 0) {
            diagnostics.add(
                    missing
                            + " typed references have no corresponding entity in this snapshot; references retained.");
        }
        token.check();
    }

    private void importEntity(EntityKind kind, long id) {
        var schema = archive.schemas.get(kind);
        String prefix = "X-" + kind.upstream() + "-" + id;
        Object scalar = archive.decode(prefix + "-S", false, token);
        Map<String, Object> raw =
                scalar == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries(scalar));
        long mask = raw.get("p") == null ? 0 : Json.number(raw.get("p"));
        if (mask < 0 || mask >= (1L << 52)) {
            throw new IllegalArgumentException("Invalid table presence mask");
        }
        var fields = new LinkedHashMap<String, Object>();
        for (int index = 1; index <= 52; index++) {
            String type = schema.type(index);
            boolean present = (mask & (1L << (index - 1))) != 0;
            String key = prefix + "-" + index;
            boolean stored = archive.metadata.containsKey(key.toLowerCase(java.util.Locale.ROOT));
            if (present != stored || present && type != null && !type.equals("table")) {
                throw new IllegalArgumentException("Table presence/key mismatch: " + key);
            }
            Object value = raw.get(Integer.toString(index));
            if (present) {
                value = archive.decode(key, false, token);
                raw.put(Integer.toString(index), value);
            }
            if (type != null) {
                fields.put(schema.name(index), schema.normalize(index, value));
            } else if (value != null) {
                fields.put("unknown_" + index, value);
            }
        }
        for (String key : raw.keySet()) {
            if (!key.equals("p") && (!key.matches("[1-9][0-9]*") || Long.parseLong(key) > 52)) {
                throw new IllegalArgumentException("Invalid scalar-row field index");
            }
        }
        db.execute(
                "INSERT INTO entities(kind,id,name,fields,raw_fields,provenance) VALUES(?,?,?,?,?,?)",
                kind.name(),
                id,
                fields.get("name"),
                Json.bytes(fields),
                Json.bytes(raw),
                prefix);
        references(kind, id, fields);
    }

    private void importLocale(String locale, EntityKind kind, Object block) {
        var schema = archive.schemas.get(kind);
        var fields = schema.localeFields();
        var rows = new TreeMap<Integer, Map<String, Object>>();
        for (var column : entries(block).entrySet()) {
            int index = position(column.getKey(), fields.size());
            String field = fields.get(index - 1);
            if (column.getValue() == null) {
                continue;
            }
            for (var entry : entries(column.getValue()).entrySet()) {
                token.check();
                int pos = position(entry.getKey(), ids.get(kind).size());
                Object translated = entry.getValue();
                if (translated == null) {
                    continue;
                }
                if (field.equals("objectivesText")) {
                    for (Object text : entries(translated).values()) {
                        if (text != null && !(text instanceof String)) {
                            throw new IllegalArgumentException("Invalid localized objective");
                        }
                    }
                } else if (!(translated instanceof String)) {
                    throw new IllegalArgumentException("Invalid localized name");
                }
                rows.computeIfAbsent(pos, ignored -> new LinkedHashMap<>()).put(field, translated);
            }
        }
        for (var row : rows.entrySet()) {
            token.check();
            long id = id(ids.get(kind).get(row.getKey() - 1));
            db.execute(
                    "INSERT INTO translations(locale,kind,id,name,fields) VALUES(?,?,?,?,?)",
                    locale,
                    kind.name(),
                    id,
                    row.getValue().get("name"),
                    Json.bytes(row.getValue()));
        }
    }

    static Map<String, Object> entries(Object value) {
        if (value instanceof Map<?, ?>) {
            return Json.object(value);
        }
        if (value instanceof List<?> list) {
            var map = new LinkedHashMap<String, Object>();
            for (int i = 0; i < list.size(); i++) {
                map.put(Integer.toString(i + 1), list.get(i));
            }
            return map;
        }
        throw new IllegalArgumentException("Expected CBOR table");
    }

    private static int position(String key, int max) {
        if (!key.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("Invalid positional key");
        }
        int index = Integer.parseInt(key);
        if (index > max) {
            throw new IllegalArgumentException("Position outside ID/field header");
        }
        return index;
    }

    static Object slot(Object value, int index) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return index <= list.size() ? list.get(index - 1) : null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.get(Integer.toString(index));
        }
        throw new IllegalArgumentException("Expected positional table");
    }

    private void references(EntityKind kind, long id, Map<String, Object> fields) {
        if (kind == EntityKind.ITEM) {
            itemList(id, fields, "npcDrops", EntityKind.NPC, Relation.DROP);
            itemList(id, fields, "objectDrops", EntityKind.OBJECT, Relation.DROP);
            itemList(id, fields, "itemDrops", EntityKind.ITEM, Relation.DROP);
            itemList(id, fields, "vendors", EntityKind.NPC, Relation.VENDOR);
            itemList(id, fields, "questRewards", EntityKind.QUEST, Relation.QUEST_REWARD);
            itemList(id, fields, "relatedQuests", EntityKind.QUEST, Relation.RELATED_QUEST);
            long quest = numberOrZero(fields.get("startQuest"));
            if (quest > 0) {
                reference(kind, id, "startQuest", EntityKind.QUEST, quest);
                itemSource(
                        id,
                        EntityKind.QUEST,
                        quest,
                        Relation.QUEST_START,
                        "DECLARED",
                        "Item.startQuest");
            }
        } else if (kind == EntityKind.NPC || kind == EntityKind.OBJECT) {
            for (String field : List.of("questStarts", "questEnds")) {
                for (long target : signedIds(fields.get(field))) {
                    reference(
                            kind,
                            id,
                            target < 0 ? field + ".negative-active" : field,
                            EntityKind.QUEST,
                            Math.abs(target));
                }
            }
        } else if (kind == EntityKind.QUEST) {
            for (String field : List.of("startedBy", "finishedBy")) {
                int groups = field.equals("startedBy") ? 3 : 2;
                for (int group = 1; group <= groups; group++) {
                    EntityKind targetKind =
                            group == 1
                                    ? EntityKind.NPC
                                    : group == 2 ? EntityKind.OBJECT : EntityKind.ITEM;
                    for (long target : idValues(slot(fields.get(field), group))) {
                        reference(kind, id, field + "[" + group + "]", targetKind, target);
                        if (targetKind == EntityKind.ITEM) {
                            itemSource(
                                    target,
                                    kind,
                                    id,
                                    Relation.QUEST_START,
                                    "REVERSE",
                                    "Quest." + field + "[3]");
                        }
                    }
                }
            }
            long provided = numberOrZero(fields.get("sourceItemId"));
            if (provided > 0) {
                reference(kind, id, "sourceItemId", EntityKind.ITEM, provided);
                itemSource(
                        provided,
                        kind,
                        id,
                        Relation.QUEST_PROVIDED,
                        "REVERSE",
                        "Quest.sourceItemId");
            }
            for (long target : idValues(fields.get("requiredSourceItems"))) {
                reference(kind, id, "requiredSourceItems", EntityKind.ITEM, target);
                itemSource(
                        target,
                        kind,
                        id,
                        Relation.QUEST_REQUIREMENT,
                        "REVERSE",
                        "Quest.requiredSourceItems");
            }
            Object objectives = fields.get("objectives");
            for (int group : List.of(1, 2, 3, 5)) {
                EntityKind targetKind =
                        group == 2
                                ? EntityKind.OBJECT
                                : group == 3 ? EntityKind.ITEM : EntityKind.NPC;
                Object rows = slot(objectives, group);
                if (rows == null) {
                    continue;
                }
                for (Object row : entries(rows).values()) {
                    if (row == null) {
                        continue;
                    }
                    if (group == 5) {
                        for (long credited : idValues(slot(row, 1))) {
                            reference(kind, id, "objectives[5].credits", EntityKind.NPC, credited);
                        }
                        continue;
                    }
                    long target = id(slot(row, 1));
                    reference(kind, id, "objectives[" + group + "]", targetKind, target);
                    if (targetKind == EntityKind.ITEM) {
                        itemSource(
                                target,
                                kind,
                                id,
                                Relation.QUEST_REQUIREMENT,
                                "REVERSE",
                                "Quest.objectives[3]");
                    }
                }
            }
            for (String field :
                    List.of(
                            "preQuestGroup",
                            "preQuestSingle",
                            "childQuests",
                            "inGroupWith",
                            "exclusiveTo",
                            "breadcrumbs")) {
                for (long target : signedIds(fields.get(field))) {
                    reference(
                            kind,
                            id,
                            target < 0 ? field + ".negative-active" : field,
                            EntityKind.QUEST,
                            Math.abs(target));
                }
            }
            for (String field :
                    List.of(
                            "nextQuestInChain",
                            "parentQuest",
                            "breadcrumbForQuestId",
                            "availableUntilCompleted",
                            "availableStartingWith",
                            "disabledByQuest")) {
                long target = numberOrZero(fields.get(field));
                if (target > 0) {
                    reference(kind, id, field, EntityKind.QUEST, target);
                }
            }
        }
    }

    private void itemList(
            long item,
            Map<String, Object> fields,
            String field,
            EntityKind kind,
            Relation relation) {
        for (long target : idValues(fields.get(field))) {
            reference(EntityKind.ITEM, item, field, kind, target);
            itemSource(item, kind, target, relation, "DECLARED", "Item." + field);
        }
    }

    private void reference(
            EntityKind from, long id, String field, EntityKind target, long targetId) {
        db.execute(
                "INSERT INTO entity_references(from_kind,from_id,field,to_kind,to_id) VALUES(?,?,?,?,?)",
                from.name(),
                id,
                field,
                target.name(),
                targetId);
    }

    private void itemSource(
            long item, EntityKind kind, long id, Relation relation, String evidence, String field) {
        db.execute(
                "INSERT OR IGNORE INTO item_sources(item_id,kind,entity_id,relation,evidence,field) VALUES(?,?,?,?,?,?)",
                item,
                kind.name(),
                id,
                relation.name(),
                evidence,
                field);
    }

    private static List<Long> signedIds(Object value) {
        if (value == null) {
            return List.of();
        }
        var result = new ArrayList<Long>();
        for (Object entry : entries(value).values()) {
            if (entry != null) {
                long n = Json.number(entry);
                if (n == 0 || n < -Integer.MAX_VALUE || n > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid signed quest reference");
                }
                result.add(n);
            }
        }
        return result;
    }

    private static List<Long> idValues(Object value) {
        if (value == null) {
            return List.of();
        }
        var result = new ArrayList<Long>();
        for (Object entry : entries(value).values()) {
            if (entry != null) {
                result.add(id(entry));
            }
        }
        return result;
    }

    private static long numberOrZero(Object value) {
        return value == null ? 0 : Json.number(value);
    }

    private static long id(Object value) {
        long id = Json.number(value);
        if (id <= 0 || id > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid entity/reference ID: " + id);
        }
        return id;
    }
}
