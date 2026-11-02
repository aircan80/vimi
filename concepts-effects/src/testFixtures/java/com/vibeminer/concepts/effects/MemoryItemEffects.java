package com.vibeminer.concepts.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.effects.EffectsTypes.EffectState;
import com.vibeminer.concepts.effects.EffectsTypes.Entry;
import com.vibeminer.concepts.effects.EffectsTypes.Link;
import com.vibeminer.concepts.effects.EffectsTypes.NameState;
import com.vibeminer.concepts.effects.EffectsTypes.OpenResult;
import com.vibeminer.concepts.effects.EffectsTypes.Page;
import com.vibeminer.concepts.effects.EffectsTypes.PageState;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import com.vibeminer.concepts.effects.EffectsTypes.Role;
import com.vibeminer.concepts.effects.EffectsTypes.Source;
import com.vibeminer.concepts.effects.EffectsTypes.SourceState;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.tables.TableTypes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independent layer6 consumer fake: seeded per build/locale; never invokes the real service or
 * table lookups.
 */
public final class MemoryItemEffects implements ItemEffects {
    public record FakeItem(
            long itemId,
            List<Entry> entries,
            Link link,
            PageState state,
            Source bridge,
            Source effect,
            Source name,
            String protectedKey) {
        public FakeItem {
            if (itemId < 0 || itemId > 0xffffffffL) {
                throw new IllegalArgumentException("uint32 item ID required");
            }
            entries = List.copyOf(entries);
            Objects.requireNonNull(link);
            Objects.requireNonNull(state);
        }

        public FakeItem(long id, List<Entry> entries) {
            this(id, entries, Link.ITEM_X_ITEM_EFFECT, PageState.OK, null, null, null, null);
        }
    }

    private record BuildKey(BuildRef build, String locale) {}

    private record Failure(FilesOpenState state, String code, String message, Instant observedAt) {}

    private final Map<BuildKey, Map<Long, FakeItem>> builds = new ConcurrentHashMap<>();
    private final Map<BuildRef, Failure> failures = new ConcurrentHashMap<>();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();

    public MemoryItemEffects add(BuildRef build, String locale, List<FakeItem> items) {
        Objects.requireNonNull(build);
        ItemEffectQueries.validateLocale(locale);
        Map<Long, FakeItem> byId = new HashMap<>();
        for (FakeItem item : items) {
            if (byId.putIfAbsent(item.itemId(), item) != null) {
                throw new IllegalArgumentException("Duplicate item ID");
            }
        }
        builds.put(new BuildKey(build, locale), Map.copyOf(byId));
        failures.remove(build);
        return this;
    }

    public MemoryItemEffects addFailing(BuildRef build, FilesOpenState state, String code) {
        return addFailing(build, state, code, null, null);
    }

    public MemoryItemEffects addFailing(
            BuildRef build, FilesOpenState state, String code, String message, Instant time) {
        Objects.requireNonNull(build);
        if (state == FilesOpenState.OPEN) {
            throw new IllegalArgumentException("Failure cannot be OPEN");
        }
        failures.put(build, new Failure(Objects.requireNonNull(state), code, message, time));
        return this;
    }

    public int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    private void count(String method) {
        calls.merge(method, 1, Integer::sum);
    }

    @Override
    public CompletableFuture<OpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        count("forBuild");
        Objects.requireNonNull(build);
        ItemEffectQueries.validateLocale(locale);
        token.check();
        Failure failure = failures.get(build);
        if (failure != null) {
            return CompletableFuture.completedFuture(
                    new OpenResult(
                            build,
                            locale,
                            failure.state(),
                            null,
                            failure.code(),
                            failure.message(),
                            failure.observedAt()));
        }
        Map<Long, FakeItem> items = builds.get(new BuildKey(build, locale));
        return CompletableFuture.completedFuture(
                items == null
                        ? new OpenResult(
                                build,
                                locale,
                                FilesOpenState.NEEDS_NETWORK,
                                null,
                                "NEEDS_NETWORK",
                                null,
                                null)
                        : new OpenResult(
                                build,
                                locale,
                                FilesOpenState.OPEN,
                                new Handle(build, locale, items),
                                null,
                                null,
                                null));
    }

    private final class Handle implements BuildItemEffects {
        private static final String IDENTITY = "fake";
        private final BuildRef build;
        private final String locale;
        private final Map<Long, FakeItem> items;
        private volatile boolean closed;

        Handle(BuildRef build, String locale, Map<Long, FakeItem> items) {
            this.build = build;
            this.locale = locale;
            this.items = items;
        }

        public BuildRef build() {
            return build;
        }

        public String locale() {
            return locale;
        }

        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }

        public CompletableFuture<Page> get(
                long id, Query query, TableTypes.Priority priority, Cancellation token) {
            count("get");
            ItemEffectQueries.validate(query);
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("uint32 item ID required");
            }
            if (closed) {
                throw new IllegalStateException("Item effects handle closed");
            }
            Objects.requireNonNull(priority);
            token.check();
            FakeItem item = items.get(id);
            if (item == null) {
                return CompletableFuture.completedFuture(
                        stop(PageState.MISSING_ITEM, id, null, null, null, null));
            }
            if (item.state() == PageState.ITEM_NOT_READABLE
                    || item.state() == PageState.MISSING_ITEM
                    || item.state() == PageState.ITEM_UNKNOWN
                    || item.state() == PageState.ITEM_PROTECTED) {
                return CompletableFuture.completedFuture(
                        stop(item.state(), id, null, null, item.protectedKey(), null));
            }
            if (id > Integer.MAX_VALUE) {
                return CompletableFuture.completedFuture(
                        stop(PageState.ITEM_KEY_OUT_OF_RANGE, id, null, null, null, null));
            }
            Source bridge =
                    item.bridge() == null
                            ? new Source(Role.BRIDGE, SourceState.READY)
                            : item.bridge();
            if (item.state() != PageState.OK) {
                return CompletableFuture.completedFuture(
                        stop(item.state(), id, item.link(), bridge, null, null));
            }
            String binding = ItemEffectQueries.binding(build, locale, id, query.limit());
            int first = 0;
            if (query.after() != null) {
                var cursor = ItemEffectQueries.decodeCursor(query.after(), binding);
                if (cursor == null
                        || !Objects.equals(cursor.i(), IDENTITY)
                        || cursor.a() >= item.entries().size()) {
                    return CompletableFuture.completedFuture(
                            stop(
                                    PageState.INVALID_CURSOR,
                                    id,
                                    item.link(),
                                    bridge,
                                    null,
                                    "INVALID_CURSOR"));
                }
                if (!Objects.equals(cursor.l(), item.link().cursorName())) {
                    return CompletableFuture.completedFuture(
                            stop(PageState.STALE, id, item.link(), bridge, null, null));
                }
                first = (int) cursor.a();
            }
            int end = Math.min(item.entries().size(), first + query.limit());
            List<Entry> entries = List.copyOf(item.entries().subList(first, end));
            String next =
                    end < item.entries().size()
                            ? ItemEffectQueries.encodeCursor(
                                    new ItemEffectQueries.Cursor(
                                            binding, end, IDENTITY, item.link().cursorName()))
                            : null;
            Source effect =
                    entries.stream()
                                    .anyMatch(
                                            e ->
                                                    e.effect().state()
                                                            != EffectState.EFFECT_KEY_OUT_OF_RANGE)
                            ? item.effect() == null
                                    ? new Source(Role.EFFECT, SourceState.READY)
                                    : item.effect()
                            : new Source(Role.EFFECT, SourceState.NOT_CHECKED);
            Source name =
                    entries.stream()
                                    .anyMatch(
                                            e ->
                                                    e.name().state() != NameState.EFFECT_NOT_READY
                                                            && e.name().state()
                                                                    != NameState
                                                                            .SPELL_KEY_OUT_OF_RANGE)
                            ? item.name() == null
                                    ? new Source(Role.NAME, SourceState.READY)
                                    : item.name()
                            : new Source(Role.NAME, SourceState.NOT_CHECKED);
            return CompletableFuture.completedFuture(
                    new Page(
                            PageState.OK,
                            id,
                            item.link(),
                            entries,
                            next,
                            item.entries().size(),
                            null,
                            bridge,
                            effect,
                            name,
                            null,
                            null,
                            null));
        }

        private Page stop(
                PageState state, long id, Link link, Source bridge, String key, String code) {
            return new Page(
                    state,
                    id,
                    link,
                    List.of(),
                    null,
                    0,
                    null,
                    bridge == null ? new Source(Role.BRIDGE, SourceState.NOT_CHECKED) : bridge,
                    new Source(Role.EFFECT, SourceState.NOT_CHECKED),
                    new Source(Role.NAME, SourceState.NOT_CHECKED),
                    key,
                    code,
                    null);
        }
    }
}
