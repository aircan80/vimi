package com.vibeminer.questie.data;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.generated.DerivedDatabase;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.data.QuestieDataTypes.EntityPage;
import com.vibeminer.questie.data.QuestieDataTypes.EntityRecord;
import com.vibeminer.questie.data.QuestieDataTypes.EntitySummary;
import com.vibeminer.questie.data.QuestieDataTypes.ItemSource;
import com.vibeminer.questie.data.QuestieDataTypes.ItemSources;
import com.vibeminer.questie.data.QuestieDataTypes.PreparedVersion;
import com.vibeminer.questie.data.QuestieDataTypes.Relation;
import com.vibeminer.questie.data.QuestieDataTypes.SourceEvidence;
import com.vibeminer.questie.internal.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Local asynchronous reads of an immutable prepared static version. */
public final class QuestieDataHandle implements AutoCloseable {
    private final DerivedDatabase database;
    private final PreparedVersion version;
    private final java.util.function.Consumer<QuestieDataHandle> onClosed;

    QuestieDataHandle(
            DerivedDatabase database,
            PreparedVersion version,
            java.util.function.Consumer<QuestieDataHandle> onClosed) {
        this.database = database;
        this.version = version;
        this.onClosed = onClosed;
    }

    public PreparedVersion version() {
        return version;
    }

    public CompletableFuture<Optional<EntityRecord>> lookup(
            EntityKind kind, long id, String locale, Cancellation token) {
        check(kind, id, locale);
        return database.read(s -> lookup(s, kind, id, locale), token);
    }

    private Optional<EntityRecord> lookup(
            DerivedDatabase.Session s, EntityKind kind, long id, String locale) {
        var rows =
                s.query(
                        "SELECT fields,provenance FROM entities WHERE kind=? AND id=?",
                        kind.name(),
                        id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> fields =
                new LinkedHashMap<>(Json.object(Json.parse((byte[]) rows.getFirst()[0], 64 << 20)));
        var translated = new ArrayList<String>();
        if (!locale.equals("enUS")) {
            var texts =
                    s.query(
                            "SELECT fields FROM translations WHERE locale=? AND kind=? AND id=?",
                            locale,
                            kind.name(),
                            id);
            if (!texts.isEmpty()) {
                var values = Json.object(Json.parse((byte[]) texts.getFirst()[0], 64 << 20));
                fields.putAll(values);
                translated.addAll(values.keySet());
            }
        }
        return Optional.of(
                new EntityRecord(
                        version.id(),
                        kind,
                        id,
                        locale,
                        fields,
                        translated,
                        (String) rows.getFirst()[1]));
    }

    public CompletableFuture<EntityPage> search(
            EntityKind kind,
            String query,
            String locale,
            int offset,
            int limit,
            Cancellation token) {
        check(kind, 1, locale);
        if (query == null || query.length() > 512 || offset < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Invalid catalog query/page");
        }
        String pattern =
                "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return database.read(
                s -> {
                    String from =
                            " FROM entities e LEFT JOIN translations t ON t.kind=e.kind AND t.id=e.id AND t.locale=? WHERE e.kind=? AND COALESCE(t.name,e.name,'') LIKE ? ESCAPE '\\'";
                    long total =
                            ((Number)
                                            s.query(
                                                            "SELECT count(*)" + from,
                                                            locale,
                                                            kind.name(),
                                                            pattern)
                                                    .getFirst()[0])
                                    .longValue();
                    var records = new ArrayList<EntityRecord>();
                    for (var row :
                            s.query(
                                    "SELECT e.id" + from + " ORDER BY e.id LIMIT ? OFFSET ?",
                                    locale,
                                    kind.name(),
                                    pattern,
                                    limit,
                                    offset)) {
                        token.check();
                        records.add(
                                lookup(s, kind, ((Number) row[0]).longValue(), locale)
                                        .orElseThrow());
                    }
                    return new EntityPage(records, total, offset, limit);
                },
                token);
    }

    public CompletableFuture<ItemSources> itemSources(
            long itemId, String locale, Cancellation token) {
        check(EntityKind.ITEM, itemId, locale);
        return database.read(
                s -> {
                    boolean present = false;
                    var sources = new ArrayList<ItemSource>();
                    for (var row :
                            s.query(
                                    "SELECT EXISTS(SELECT 1 FROM entities WHERE kind='ITEM' AND id=w.item_id),"
                                            + "r.kind,r.entity_id,r.relation,r.evidence,r.field,e.id,COALESCE(t.name,e.name),"
                                            + "CASE WHEN t.name IS NOT NULL THEN 1 ELSE 0 END,e.provenance "
                                            + "FROM (SELECT ? AS item_id) w "
                                            + "LEFT JOIN item_sources r ON r.item_id=w.item_id "
                                            + "LEFT JOIN entities e ON e.kind=r.kind AND e.id=r.entity_id "
                                            + "LEFT JOIN translations t ON t.locale=? AND t.kind=e.kind AND t.id=e.id "
                                            + "ORDER BY r.relation,r.kind,r.entity_id,r.evidence,r.field",
                                    itemId,
                                    locale)) {
                        token.check();
                        present = ((Number) row[0]).longValue() != 0;
                        if (row[1] == null) {
                            continue;
                        }
                        var kind = EntityKind.parse((String) row[1]);
                        long id = ((Number) row[2]).longValue();
                        Optional<EntitySummary> summary =
                                row[6] == null
                                        ? Optional.empty()
                                        : Optional.of(
                                                new EntitySummary(
                                                        version.id(),
                                                        kind,
                                                        id,
                                                        locale,
                                                        (String) row[7],
                                                        ((Number) row[8]).longValue() != 0,
                                                        (String) row[9]));
                        sources.add(
                                new ItemSource(
                                        Relation.valueOf((String) row[3]),
                                        kind,
                                        id,
                                        SourceEvidence.valueOf((String) row[4]),
                                        (String) row[5],
                                        summary));
                    }
                    return new ItemSources(
                            version.id(),
                            itemId,
                            present,
                            sources,
                            "Recorded static QuestieDB relationships only; empty does not prove absence. Runtime corrections, availability, drop rates, display IDs and FileDataIds are not inferred. Quest requirements/starts/related quests are associations, not acquisition guarantees.");
                },
                token);
    }

    private static void check(EntityKind kind, long id, String locale) {
        if (kind == null
                || id <= 0
                || id > Integer.MAX_VALUE
                || locale == null
                || locale.isBlank()
                || locale.length() > 64) {
            throw new IllegalArgumentException("Invalid Questie entity/locale");
        }
    }

    public CompletableFuture<Void> closeAsync() {
        return database.closeAsync().whenComplete((ignored, failure) -> onClosed.accept(this));
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
