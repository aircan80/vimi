package com.vibeminer.concepts.effects;

import static com.vibeminer.concepts.effects.ItemEffectProfiles.BRIDGE_TABLE;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.EFFECT_TABLE;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.ITEM_TABLE;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.NAME_TABLE;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.SPELL_NAME;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.TEXT_INVALID_CODE;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.bridge;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.choose;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.evaluate;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.failed;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.fields;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.parentColumn;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.parentColumnName;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.signedNumber;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.source;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.spellId;
import static com.vibeminer.concepts.effects.ItemEffectProfiles.wellFormed;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.concepts.effects.EffectsTypes.Absence;
import com.vibeminer.concepts.effects.EffectsTypes.Effect;
import com.vibeminer.concepts.effects.EffectsTypes.EffectState;
import com.vibeminer.concepts.effects.EffectsTypes.Entry;
import com.vibeminer.concepts.effects.EffectsTypes.Field;
import com.vibeminer.concepts.effects.EffectsTypes.Link;
import com.vibeminer.concepts.effects.EffectsTypes.Name;
import com.vibeminer.concepts.effects.EffectsTypes.NameState;
import com.vibeminer.concepts.effects.EffectsTypes.OpenResult;
import com.vibeminer.concepts.effects.EffectsTypes.Options;
import com.vibeminer.concepts.effects.EffectsTypes.Page;
import com.vibeminer.concepts.effects.EffectsTypes.PageState;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import com.vibeminer.concepts.effects.EffectsTypes.Role;
import com.vibeminer.concepts.effects.EffectsTypes.Source;
import com.vibeminer.concepts.effects.EffectsTypes.SourceState;
import com.vibeminer.concepts.effects.ItemEffectProfiles.Profile;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Retail bridge and Classic ParentItemID links, with raw inputs and exact spell-name observations.
 */
public final class ItemEffectsService implements ItemEffects {
    private final Tables tables;
    private final Options options;

    public ItemEffectsService(Tables tables) {
        this(tables, new Options());
    }

    public ItemEffectsService(Tables tables, Options options) {
        this.tables = Objects.requireNonNull(tables);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public CompletableFuture<OpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        Objects.requireNonNull(build);
        ItemEffectQueries.validateLocale(locale);
        Objects.requireNonNull(token);
        token.check();
        CompletableFuture<OpenResult> result = new CompletableFuture<>();
        tables.forBuild(build, locale, token)
                .whenComplete(
                        (opened, error) -> {
                            if (error != null) {
                                result.completeExceptionally(EffectsTasks.unwrap(error));
                                return;
                            }
                            BuildItemEffects handle = null;
                            try {
                                Objects.requireNonNull(opened, "Tables open result");
                                token.check();
                                if (opened.state() != FilesOpenState.OPEN
                                        || opened.tables() == null) {
                                    if (opened.tables() != null) {
                                        opened.tables().close();
                                    }
                                    result.complete(
                                            new OpenResult(
                                                    build,
                                                    locale,
                                                    opened.state(),
                                                    null,
                                                    opened.code(),
                                                    opened.message(),
                                                    opened.observedAt()));
                                    return;
                                }
                                handle = new Handle(opened.tables(), options);
                                if (!result.complete(
                                        new OpenResult(
                                                build,
                                                locale,
                                                FilesOpenState.OPEN,
                                                handle,
                                                null,
                                                null,
                                                null))) {
                                    handle.close();
                                }
                            } catch (Throwable failure) {
                                try {
                                    if (handle != null) {
                                        handle.closeAsync();
                                    } else if (opened != null && opened.tables() != null) {
                                        opened.tables().closeAsync();
                                    }
                                } catch (Throwable cleanupFailure) {
                                    if (cleanupFailure != failure) {
                                        failure.addSuppressed(cleanupFailure);
                                    }
                                }
                                result.completeExceptionally(failure);
                            }
                        });
        return result;
    }

    private static final class RowMismatch extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
    }

    private static final class Handle implements BuildItemEffects {
        private static final TableTypes.Ref ITEM = TableTypes.Ref.forName(ITEM_TABLE),
                BRIDGE = TableTypes.Ref.forName(BRIDGE_TABLE),
                EFFECT = TableTypes.Ref.forName(EFFECT_TABLE),
                NAME = TableTypes.Ref.forName(NAME_TABLE);
        private static final Source NO_BRIDGE = new Source(Role.BRIDGE, SourceState.NOT_CHECKED),
                NO_EFFECT = new Source(Role.EFFECT, SourceState.NOT_CHECKED),
                NO_NAME = new Source(Role.NAME, SourceState.NOT_CHECKED);
        private final BuildTables tables;
        private final RelationIndexes relations;
        private final Object gate = new Object();
        private final CompletableFuture<Void> closing = new CompletableFuture<>();
        private int active;
        private boolean closed, cleanup;

        Handle(BuildTables tables, Options options) {
            this.tables = tables;
            relations = new RelationIndexes(tables, options.maxRelationIndexEntries());
        }

        public BuildRef build() {
            return tables.build();
        }

        public String locale() {
            return tables.locale();
        }

        public CompletableFuture<Page> get(
                long itemId, Query query, TableTypes.Priority priority, Cancellation token) {
            ItemEffectQueries.validate(query);
            if (itemId < 0 || itemId > 0xffffffffL) {
                throw new IllegalArgumentException("uint32 item ID required");
            }
            return call(priority, token, () -> getCore(itemId, query, priority, token));
        }

        @Override
        public CompletableFuture<EffectsTypes.Tooltip> tooltip(
                long itemId, TableTypes.Priority priority, Cancellation token) {
            if (itemId < 0 || itemId > 0xffffffffL) {
                throw new IllegalArgumentException("uint32 item ID required");
            }
            return call(
                    priority,
                    token,
                    () -> {
                        var page = getCore(itemId, new Query(), priority, token);
                        var lines = new ArrayList<EffectsTypes.TooltipLine>();
                        for (var entry : page.entries()) {
                            token.check();
                            var effect = entry.effect();
                            Long spell =
                                    effect.spellId() == null
                                            ? null
                                            : signedNumber(effect.spellId(), 1, 0xffffffffL);
                            if (spell == null) {
                                lines.add(
                                        new EffectsTypes.TooltipLine(
                                                "Effect",
                                                0,
                                                null,
                                                null,
                                                effect.state() == EffectState.PROTECTED
                                                        ? EffectsTypes.TextState.PROTECTED
                                                        : EffectsTypes.TextState.UNKNOWN));
                                continue;
                            }
                            String trigger =
                                    effect.fields() == null
                                            ? "Effect"
                                            : effect.fields().stream()
                                                    .filter(
                                                            field ->
                                                                    field.name()
                                                                            .equals("TriggerType"))
                                                    .map(
                                                            field ->
                                                                    switch (field.value()) {
                                                                        case "0" -> "Use";
                                                                        case "1" -> "Equip";
                                                                        case "2" -> "Chance on hit";
                                                                        default ->
                                                                                "Effect (trigger "
                                                                                        + field
                                                                                                .value()
                                                                                        + ")";
                                                                    })
                                                    .findFirst()
                                                    .orElse("Effect");
                            lines.add(
                                    description(
                                            trigger,
                                            spell,
                                            entry.name() == null ? null : entry.name().text(),
                                            priority,
                                            token));
                        }
                        return new EffectsTypes.Tooltip(
                                itemId, page.state(), lines, page.next() != null);
                    });
        }

        private EffectsTypes.TooltipLine description(
                String trigger,
                long spell,
                String name,
                TableTypes.Priority priority,
                Cancellation token) {
            var ref = TableTypes.Ref.forName("Spell");
            var schema = tables.schema(ref, priority, token).join();
            int column = -1;
            if (schema.schema() != null && schema.columns() != null) {
                for (int i = 0; i < schema.schema().columns().size(); i++) {
                    var field = schema.schema().columns().get(i);
                    if (field.verifiedName()
                            && field.name().equals("Description_lang")
                            && field.kind()
                                    == com.vibeminer.formats.definitions.TableSchema.ColumnKind
                                            .LOCSTRING
                            && field.arrayLength() == 1
                            && i < schema.columns().size()
                            && "Description_lang".equals(schema.columns().get(i).name())
                            && "string".equals(schema.columns().get(i).kind())) {
                        column = i;
                    }
                }
            }
            if (column < 0) {
                return new EffectsTypes.TooltipLine(
                        trigger, spell, name, null, EffectsTypes.TextState.UNSUPPORTED);
            }
            var found = tables.get(ref, spell, priority, token).join();
            if (found.outcome() != TableTypes.RowOutcome.FOUND || found.row() == null) {
                return new EffectsTypes.TooltipLine(
                        trigger,
                        spell,
                        name,
                        null,
                        found.outcome() == TableTypes.RowOutcome.PROTECTED
                                ? EffectsTypes.TextState.PROTECTED
                                : found.outcome() == TableTypes.RowOutcome.ABSENT
                                        ? EffectsTypes.TextState.ABSENT
                                        : EffectsTypes.TextState.UNKNOWN);
            }
            var row = found.row().row();
            if (row.id() != spell
                    || column >= row.values().size()
                    || ItemEffectProfiles.hasIssue(row, column)) {
                return new EffectsTypes.TooltipLine(
                        trigger, spell, name, null, EffectsTypes.TextState.INVALID_TEXT);
            }
            return ItemEffectText.project(trigger, spell, name, row.values().get(column));
        }

        @Override
        public CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
                TableTypes.Priority priority, Cancellation token) {
            return call(
                    priority,
                    token,
                    () -> {
                        var effect = tables.schema(EFFECT, priority, token).join();
                        var bridgeSchema = tables.schema(BRIDGE, priority, token).join();
                        Link link = choose(effect.schema(), bridgeSchema.schema());
                        if (link == Link.PARENT_ITEM_ID) {
                            bridgeSchema = effect;
                        }
                        Profile profile = bridge(link);
                        Source source = evaluate(Role.BRIDGE, profile, bridgeSchema);
                        if (source.state() != SourceState.READY) {
                            return new com.vibeminer.concepts.core.ConceptWarmup.Result(
                                    List.of(
                                            new com.vibeminer.concepts.core.ConceptWarmup.Entry(
                                                    profile.table(),
                                                    parentColumnName(link),
                                                    false,
                                                    source.state().name(),
                                                    "Effect bridge profile not ready",
                                                    null)));
                        }
                        String column = parentColumnName(link);
                        var warmed =
                                relations.prepare(profile.table(), column, priority, token).join();
                        return new com.vibeminer.concepts.core.ConceptWarmup.Result(
                                List.of(
                                        com.vibeminer.concepts.core.ConceptWarmup.relation(
                                                profile.table(), column, warmed)));
                    });
        }

        private <T> CompletableFuture<T> call(
                TableTypes.Priority priority,
                Cancellation token,
                java.util.function.Supplier<T> action) {
            Objects.requireNonNull(priority);
            Objects.requireNonNull(token);
            synchronized (gate) {
                if (closed) {
                    throw new IllegalStateException("Item effects handle closed");
                }
                active++;
            }
            var left = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable leave =
                    () -> {
                        if (left.compareAndSet(false, true)) {
                            release();
                        }
                    };
            var future =
                    EffectsTasks.run(
                            () -> {
                                try {
                                    token.check();
                                    T page = action.get();
                                    token.check();
                                    return page;
                                } finally {
                                    leave.run();
                                }
                            });
            future.whenComplete((v, e) -> leave.run());
            return future.copy();
        }

        private void release() {
            boolean begin;
            synchronized (gate) {
                active--;
                begin = closed && active == 0 && !cleanup;
                if (begin) {
                    cleanup = true;
                }
            }
            if (begin) {
                cleanup();
            }
        }

        public CompletableFuture<Void> closeAsync() {
            boolean begin;
            synchronized (gate) {
                closed = true;
                begin = active == 0 && !cleanup;
                if (begin) {
                    cleanup = true;
                }
            }
            if (begin) {
                cleanup();
            }
            return closing.copy();
        }

        private void cleanup() {
            try {
                tables.closeAsync()
                        .whenComplete(
                                (v, e) -> {
                                    if (e == null) {
                                        closing.complete(null);
                                    } else {
                                        closing.completeExceptionally(EffectsTasks.unwrap(e));
                                    }
                                });
            } catch (Throwable e) {
                closing.completeExceptionally(e);
            }
        }

        private static Page stop(
                PageState state,
                long itemId,
                TableTypes.Entry item,
                Link link,
                Source bridge,
                String key,
                String code,
                String message,
                int matches) {
            return new Page(
                    state,
                    itemId,
                    link,
                    List.of(),
                    null,
                    matches,
                    item,
                    bridge == null ? NO_BRIDGE : bridge,
                    NO_EFFECT,
                    NO_NAME,
                    key,
                    code,
                    message);
        }

        private Page getCore(
                long itemId, Query query, TableTypes.Priority priority, Cancellation token) {
            var item = tables.get(ITEM, itemId, priority, token).join();
            if (!TableQueries.isReadable(item.table().state())) {
                return stop(
                        PageState.ITEM_NOT_READABLE,
                        itemId,
                        item.table(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0);
            }
            switch (item.outcome()) {
                case FOUND:
                    break;
                case ABSENT:
                    return stop(
                            PageState.MISSING_ITEM,
                            itemId,
                            item.table(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            0);
                case PROTECTED:
                    return stop(
                            PageState.ITEM_PROTECTED,
                            itemId,
                            item.table(),
                            null,
                            null,
                            item.protectedKey(),
                            null,
                            null,
                            0);
                default:
                    return stop(
                            PageState.ITEM_UNKNOWN,
                            itemId,
                            item.table(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            0);
            }
            if (itemId > Integer.MAX_VALUE) {
                return stop(
                        PageState.ITEM_KEY_OUT_OF_RANGE,
                        itemId,
                        item.table(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0);
            }
            var effectSchema = tables.schema(EFFECT, priority, token).join();
            TableTypes.SchemaResult bridgeSchema = null;
            if (effectSchema.schema() == null) {
                bridgeSchema = tables.schema(BRIDGE, priority, token).join();
            }
            Link link =
                    choose(
                            effectSchema.schema(),
                            bridgeSchema == null ? null : bridgeSchema.schema());
            if (link == Link.PARENT_ITEM_ID) {
                bridgeSchema = effectSchema;
            } else if (bridgeSchema == null) {
                bridgeSchema = tables.schema(BRIDGE, priority, token).join();
            }
            Profile bridgeProfile = bridge(link);
            Source bridge = evaluate(Role.BRIDGE, bridgeProfile, bridgeSchema);
            if (bridge.state() != SourceState.READY) {
                return stop(
                        PageState.BRIDGE_NOT_READY,
                        itemId,
                        item.table(),
                        link,
                        bridge,
                        null,
                        null,
                        null,
                        0);
            }
            String binding = ItemEffectQueries.binding(build(), locale(), itemId, query.limit());
            ItemEffectQueries.Cursor cursor = null;
            if (query.after() != null) {
                cursor = ItemEffectQueries.decodeCursor(query.after(), binding);
                if (cursor == null) {
                    return stop(
                            PageState.INVALID_CURSOR,
                            itemId,
                            item.table(),
                            link,
                            bridge,
                            null,
                            "INVALID_CURSOR",
                            "The effects cursor belongs to another item, page size or build.",
                            0);
                }
                if (!Objects.equals(cursor.l(), link.cursorName())) {
                    return stop(
                            PageState.STALE,
                            itemId,
                            item.table(),
                            link,
                            bridge,
                            null,
                            null,
                            null,
                            0);
                }
            }
            int skip = (int) Math.min(cursor == null ? 0 : cursor.a(), Integer.MAX_VALUE);
            var found =
                    relations
                            .lookup(
                                    bridgeProfile.table(),
                                    parentColumnName(link),
                                    itemId,
                                    query.limit(),
                                    priority,
                                    token,
                                    skip)
                            .join();
            switch (found.state()) {
                case OK:
                    break;
                case INDEX_LIMIT:
                    return stop(
                            PageState.INDEX_LIMIT,
                            itemId,
                            item.table(),
                            link,
                            bridge,
                            null,
                            found.code(),
                            found.message(),
                            0);
                case STALE:
                    return stop(
                            PageState.STALE,
                            itemId,
                            item.table(),
                            link,
                            bridge,
                            null,
                            null,
                            found.message(),
                            0);
                case UNSUPPORTED:
                    return stop(
                            PageState.BRIDGE_NOT_READY,
                            itemId,
                            item.table(),
                            link,
                            failed(bridge, SourceState.UNSUPPORTED, found.table()),
                            null,
                            found.code(),
                            found.message(),
                            0);
                default:
                    {
                        Source failure = source(Role.BRIDGE, found.table());
                        failure =
                                new Source(
                                        failure.role(),
                                        failure.state(),
                                        failure.table(),
                                        failure.problem(),
                                        bridge.definition());
                        return stop(
                                PageState.BRIDGE_NOT_READY,
                                itemId,
                                item.table(),
                                link,
                                failure,
                                null,
                                null,
                                null,
                                0);
                    }
            }
            if (cursor != null
                    && (!Objects.equals(cursor.i(), found.identity())
                            || cursor.a() >= found.matches())) {
                return !Objects.equals(cursor.i(), found.identity())
                        ? stop(
                                PageState.STALE,
                                itemId,
                                item.table(),
                                link,
                                bridge,
                                null,
                                null,
                                null,
                                0)
                        : stop(
                                PageState.INVALID_CURSOR,
                                itemId,
                                item.table(),
                                link,
                                bridge,
                                null,
                                "INVALID_CURSOR",
                                "The effects cursor points past the item's links.",
                                0);
            }
            int consumed = skip + found.rows().size();
            List<LinkRow> links = new ArrayList<>();
            try {
                for (var row : found.rows()) {
                    token.check();
                    var values = row.row().values();
                    if (values.size() != bridgeProfile.columns().size()
                            || !values.getFirst().equals(Long.toString(row.row().id()))
                            || !values.get(parentColumn(link)).equals(Long.toString(itemId))) {
                        throw new RowMismatch();
                    }
                    String effectId =
                            link == Link.ITEM_X_ITEM_EFFECT ? values.get(1) : values.getFirst();
                    Long key = signedNumber(effectId, Integer.MIN_VALUE, Integer.MAX_VALUE);
                    if (key == null) {
                        throw new RowMismatch();
                    }
                    links.add(new LinkRow(row, values.get(parentColumn(link)), effectId, key));
                }
            } catch (RowMismatch bad) {
                return stop(
                        PageState.BRIDGE_NOT_READY,
                        itemId,
                        item.table(),
                        link,
                        failed(bridge, SourceState.CORRUPT, bridge.table()),
                        null,
                        null,
                        null,
                        found.matches());
            }
            Source effect = NO_EFFECT;
            Map<Long, Effect> effects = new LinkedHashMap<>();
            if (links.stream().anyMatch(row -> row.key >= 0)) {
                Profile profile = ItemEffectProfiles.effect(link);
                effect = evaluate(Role.EFFECT, profile, effectSchema);
                if (effect.state() == SourceState.READY) {
                    try {
                        for (long key :
                                links.stream()
                                        .map(LinkRow::key)
                                        .filter(k -> k >= 0)
                                        .distinct()
                                        .toList()) {
                            token.check();
                            effects.put(
                                    key,
                                    effect(profile, effectSchema.columns(), key, priority, token));
                        }
                    } catch (RowMismatch bad) {
                        effect = failed(effect, SourceState.CORRUPT, effect.table());
                        effects.clear();
                    }
                }
            }
            Source name = NO_NAME;
            Map<Long, Name> names = new HashMap<>();
            List<Long> spells =
                    effects.values().stream()
                            .map(Effect::spellId)
                            .filter(Objects::nonNull)
                            .map(Long::parseLong)
                            .filter(spell -> spell >= 0)
                            .distinct()
                            .toList();
            if (!spells.isEmpty()) {
                name = evaluate(Role.NAME, SPELL_NAME, tables.schema(NAME, priority, token).join());
                if (name.state() == SourceState.READY) {
                    try {
                        for (long spell : spells) {
                            token.check();
                            names.put(spell, name(spell, priority, token));
                        }
                    } catch (RowMismatch bad) {
                        name = failed(name, SourceState.CORRUPT, name.table());
                        names.clear();
                    }
                }
            }
            List<Entry> entries = new ArrayList<>();
            for (LinkRow row : links) {
                Effect observed =
                        row.key < 0
                                ? new Effect(EffectState.EFFECT_KEY_OUT_OF_RANGE)
                                : effects.getOrDefault(
                                        row.key, new Effect(EffectState.SOURCE_NOT_READY));
                Name named =
                        observed.spellId() == null
                                ? new Name(NameState.EFFECT_NOT_READY)
                                : Long.parseLong(observed.spellId()) < 0
                                        ? new Name(NameState.SPELL_KEY_OUT_OF_RANGE)
                                        : names.getOrDefault(
                                                Long.parseLong(observed.spellId()),
                                                new Name(NameState.SOURCE_NOT_READY));
                entries.add(new Entry(row.row, row.parent, row.effectId, observed, named));
            }
            String next =
                    consumed < found.matches()
                            ? ItemEffectQueries.encodeCursor(
                                    new ItemEffectQueries.Cursor(
                                            binding, consumed, found.identity(), link.cursorName()))
                            : null;
            return new Page(
                    PageState.OK,
                    itemId,
                    link,
                    entries,
                    next,
                    found.matches(),
                    item.table(),
                    bridge,
                    effect,
                    name,
                    null,
                    null,
                    null);
        }

        private record LinkRow(TableTypes.Row row, String parent, String effectId, long key) {}

        private Effect effect(
                Profile profile,
                List<Db2Types.Column> columns,
                long id,
                TableTypes.Priority priority,
                Cancellation token) {
            var found = tables.get(EFFECT, id, priority, token).join();
            switch (found.outcome()) {
                case FOUND:
                    break;
                case ABSENT:
                    return new Effect(
                            EffectState.NOT_FOUND_IN_ADMITTED_SCOPE,
                            null,
                            null,
                            null,
                            Absence.ABSENT_FROM_COMPLETE_SOURCE,
                            null);
                case PROTECTED:
                    return new Effect(
                            EffectState.PROTECTED, null, null, null, null, found.protectedKey());
                default:
                    return new Effect(
                            EffectState.NOT_FOUND_IN_ADMITTED_SCOPE,
                            null,
                            null,
                            null,
                            Absence.UNKNOWN_OUTSIDE_READABLE_SCOPE,
                            null);
            }
            var row = found.row();
            if (row == null
                    || row.row().id() != id
                    || row.row().values().size() != profile.columns().size()
                    || !row.row().values().getFirst().equals(Long.toString(id))
                    || row.row().isCopy() != (row.row().copySourceId() != null)) {
                throw new RowMismatch();
            }
            String spell = spellId(row.row());
            List<Field> fields = fields(profile, columns, row.row());
            return new Effect(
                    fields == null ? EffectState.FIELD_UNAVAILABLE : EffectState.OBSERVED_READY,
                    row,
                    fields,
                    spell,
                    null,
                    null);
        }

        private Name name(long id, TableTypes.Priority priority, Cancellation token) {
            var found = tables.get(NAME, id, priority, token).join();
            switch (found.outcome()) {
                case FOUND:
                    break;
                case ABSENT:
                    return new Name(
                            NameState.NOT_FOUND_IN_ADMITTED_SCOPE,
                            null,
                            null,
                            null,
                            null,
                            Absence.ABSENT_FROM_COMPLETE_SOURCE,
                            null);
                case PROTECTED:
                    return new Name(
                            NameState.PROTECTED,
                            null,
                            null,
                            null,
                            null,
                            null,
                            found.protectedKey());
                default:
                    return new Name(
                            NameState.NOT_FOUND_IN_ADMITTED_SCOPE,
                            null,
                            null,
                            null,
                            null,
                            Absence.UNKNOWN_OUTSIDE_READABLE_SCOPE,
                            null);
            }
            var row = found.row();
            if (row == null
                    || row.row().id() != id
                    || row.row().values().size() != 2
                    || !row.row().values().getFirst().equals(Long.toString(id))
                    || row.row().isCopy() != (row.row().copySourceId() != null)) {
                throw new RowMismatch();
            }
            var issues =
                    row.row().textIssues() == null
                            ? List.<Db2Types.TextIssue>of()
                            : row.row().textIssues().stream()
                                    .filter(issue -> issue.columnIndex() == 1)
                                    .toList();
            if (!issues.isEmpty()) {
                if (issues.size() != 1 || !TEXT_INVALID_CODE.equals(issues.getFirst().code())) {
                    throw new RowMismatch();
                }
                return new Name(
                        NameState.INVALID_TEXT, row, null, null, TEXT_INVALID_CODE, null, null);
            }
            String text = row.row().values().get(1);
            if (text.length() > ItemEffectQueries.MAX_SOURCE_NAME_UNITS || !wellFormed(text)) {
                throw new RowMismatch();
            }
            if (text.length() > ItemEffectQueries.MAX_NAME_UNITS) {
                return new Name(
                        NameState.TEXT_TOO_LARGE, row, null, text.length(), null, null, null);
            }
            return new Name(
                    text.isEmpty() ? NameState.EMPTY_TEXT : NameState.READY,
                    row,
                    text,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
