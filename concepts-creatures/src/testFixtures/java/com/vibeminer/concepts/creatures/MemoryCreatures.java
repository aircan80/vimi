package com.vibeminer.concepts.creatures;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearance;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearanceState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDetail;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplaySlot;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureModel;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureModelState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSourceValue;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSummary;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesOpenResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPage;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPageState;
import com.vibeminer.files.FileLocales;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Independent seeded implementation for consumers of the creature boundary. */
public final class MemoryCreatures implements Creatures {
    public record FakeCreature(
            long id,
            CreatureName name,
            List<CreatureSourceValue> facts,
            List<CreatureDisplaySlot> slots,
            List<CreatureDisplayChain> displays,
            String classificationLabel,
            boolean isCopy,
            Long copySourceId,
            TableTypes.Row row,
            TableTypes.RowOutcome outcome,
            String protectedKey) {
        public FakeCreature {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("ID");
            }
            Objects.requireNonNull(name);
            Objects.requireNonNull(outcome);
            facts = facts == null ? null : List.copyOf(facts);
            slots = slots == null ? null : List.copyOf(slots);
            displays = displays == null ? null : List.copyOf(displays);
        }

        public FakeCreature(long id, CreatureName name) {
            this(
                    id,
                    name,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    TableTypes.RowOutcome.FOUND,
                    null);
        }
    }

    public record FakeDisplays(
            Map<Long, Long> displays, Map<Long, Long> models, Map<Long, CreatureModel> files) {
        public static final FakeDisplays NONE = new FakeDisplays(Map.of(), Map.of(), Map.of());

        public FakeDisplays {
            displays = displays == null ? Map.of() : Map.copyOf(displays);
            models = models == null ? Map.of() : Map.copyOf(models);
            files = files == null ? Map.of() : Map.copyOf(files);
        }
    }

    private record Key(BuildRef build, String locale) {}

    private record Seed(List<FakeCreature> creatures, FakeDisplays displays) {}

    private record Failure(FilesOpenState state, String code) {}

    private final Map<Key, Seed> builds = new HashMap<>();
    private final Map<BuildRef, Failure> failures = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final int maxScanRows;

    public MemoryCreatures() {
        this(CreaturesOptions.DEFAULT_MAX_SCAN_ROWS);
    }

    public MemoryCreatures(int maxScanRows) {
        if (maxScanRows < 1) {
            throw new IllegalArgumentException("Scan bound");
        }
        this.maxScanRows = maxScanRows;
    }

    public synchronized MemoryCreatures add(
            BuildRef build, String locale, List<FakeCreature> creatures, FakeDisplays displays) {
        Objects.requireNonNull(build);
        FileLocales.bitOf(locale);
        var list = List.copyOf(creatures);
        if (list.stream().map(FakeCreature::id).distinct().count() != list.size()) {
            throw new IllegalArgumentException("Duplicate ID");
        }
        builds.put(
                new Key(build, locale),
                new Seed(list, displays == null ? FakeDisplays.NONE : displays));
        failures.remove(build);
        return this;
    }

    public MemoryCreatures add(BuildRef build, String locale, List<FakeCreature> creatures) {
        return add(build, locale, creatures, null);
    }

    public synchronized MemoryCreatures addFailing(
            BuildRef build, FilesOpenState state, String code) {
        Objects.requireNonNull(build);
        if (state == FilesOpenState.OPEN) {
            throw new IllegalArgumentException("Failure is open");
        }
        failures.put(build, new Failure(Objects.requireNonNull(state), code));
        return this;
    }

    public synchronized int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    private synchronized void count(String method) {
        calls.merge(method, 1, Integer::sum);
    }

    @Override
    public synchronized CompletableFuture<CreaturesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        count("forBuild");
        Objects.requireNonNull(build);
        FileLocales.bitOf(locale);
        token.check();
        var failure = failures.get(build);
        if (failure != null) {
            return CompletableFuture.completedFuture(
                    new CreaturesOpenResult(
                            build,
                            locale,
                            failure.state(),
                            null,
                            failure.code(),
                            "Scripted failure",
                            null));
        }
        var seed = builds.get(new Key(build, locale));
        if (seed != null) {
            return CompletableFuture.completedFuture(
                    new CreaturesOpenResult(
                            build,
                            locale,
                            FilesOpenState.OPEN,
                            new Handle(build, locale, seed),
                            null,
                            null,
                            null));
        }
        boolean known = builds.keySet().stream().anyMatch(key -> key.build().equals(build));
        return CompletableFuture.completedFuture(
                new CreaturesOpenResult(
                        build,
                        locale,
                        known ? FilesOpenState.LOCALE_NOT_IN_BUILD : FilesOpenState.NEEDS_NETWORK,
                        null,
                        known ? "FILES_LOCALE_NOT_IN_BUILD" : "BUILD_NEEDS_NETWORK",
                        null,
                        null));
    }

    private static List<CreatureDisplaySlot> slots(FakeCreature creature) {
        if (creature.slots() != null) {
            return creature.slots();
        }
        return java.util.stream.IntStream.range(0, 4)
                .mapToObj(
                        i ->
                                new CreatureDisplaySlot(
                                        i, 0, 0, CreatureSlotState.NO_DECLARED_DISPLAY))
                .toList();
    }

    private static CreatureSummary summary(FakeCreature creature) {
        return new CreatureSummary(
                creature.id(),
                creature.isCopy(),
                creature.copySourceId(),
                creature.name(),
                creature.facts() == null ? List.of() : creature.facts(),
                slots(creature));
    }

    private static CreatureDisplayChain chain(CreatureDisplaySlot slot, FakeDisplays displays) {
        CreatureDisplayChainState state;
        Long model = null, file = null;
        CreatureModel read = null;
        if (slot.state() == CreatureSlotState.NO_DECLARED_DISPLAY) {
            state = CreatureDisplayChainState.NO_DECLARED_DISPLAY;
        } else if (slot.state() == CreatureSlotState.INVALID_DISPLAY_REFERENCE) {
            state = CreatureDisplayChainState.INVALID_DISPLAY_REFERENCE;
        } else if ((model = displays.displays().get((long) slot.displayId())) == null) {
            state = CreatureDisplayChainState.DISPLAY_ABSENT;
        } else if (model == 0) {
            state = CreatureDisplayChainState.NO_DECLARED_MODEL;
        } else if ((file = displays.models().get(model)) == null) {
            state = CreatureDisplayChainState.MODEL_ABSENT;
        } else if (file == 0) {
            state = CreatureDisplayChainState.NO_DECLARED_MODEL_FILE;
        } else {
            state = CreatureDisplayChainState.METADATA_RESOLVED;
            read = displays.files().get(file);
            if (read == null) {
                read =
                        new CreatureModel(
                                CreatureModelState.READ_FAILED,
                                file,
                                BuildModelTypes.ReadState.UNKNOWN_FILE,
                                new CreatureAppearance(
                                        CreatureAppearanceState.MODEL_NOT_READ,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                List.of(),
                                null,
                                null,
                                "The model is UnknownFile",
                                null,
                                null,
                                null,
                                null);
            }
        }
        return new CreatureDisplayChain(
                slot, state, null, null, model, null, null, file, null, null, null, null, null,
                null, read);
    }

    private final class Handle implements BuildCreatures {
        private final BuildRef build;
        private final String locale;
        private final Seed seed;
        private boolean closed;

        Handle(BuildRef build, String locale, Seed seed) {
            this.build = build;
            this.locale = locale;
            this.seed = seed;
        }

        @Override
        public BuildRef build() {
            return build;
        }

        @Override
        public String locale() {
            return locale;
        }

        private void enter(String method, TableTypes.Priority priority, Cancellation token) {
            count(method);
            if (closed) {
                throw new IllegalStateException("Closed");
            }
            Objects.requireNonNull(priority);
            token.check();
        }

        @Override
        public synchronized CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<CreaturesPage> list(
                CreatureQuery query, TableTypes.Priority priority, Cancellation token) {
            CreatureQueries.validate(query);
            enter("list", priority, token);
            String binding = CreatureQueries.binding(build, locale, query);
            if (query.exactId() != null) {
                if (query.after() != null) {
                    return CompletableFuture.completedFuture(invalid());
                }
                var found =
                        seed.creatures().stream()
                                .filter(c -> c.id() == query.exactId())
                                .findFirst()
                                .orElse(null);
                var outcome = found == null ? TableTypes.RowOutcome.ABSENT : found.outcome();
                return CompletableFuture.completedFuture(
                        new CreaturesPage(
                                CreaturesPageState.OK,
                                outcome == TableTypes.RowOutcome.FOUND
                                        ? List.of(summary(found))
                                        : List.of(),
                                null,
                                1,
                                null,
                                outcome,
                                outcome == TableTypes.RowOutcome.PROTECTED
                                        ? found.protectedKey()
                                        : null,
                                null,
                                null));
            }
            var readable =
                    seed.creatures().stream()
                            .filter(c -> c.outcome() == TableTypes.RowOutcome.FOUND)
                            .toList();
            long after = -1;
            if (query.after() != null) {
                var cursor = CreatureQueries.decodeCursor(query.after(), binding);
                if (cursor == null || cursor.a() >= readable.size()) {
                    return CompletableFuture.completedFuture(invalid());
                }
                if (!Objects.equals(cursor.i(), "fake-creatures")) {
                    return CompletableFuture.completedFuture(
                            new CreaturesPage(
                                    CreaturesPageState.STALE,
                                    List.of(),
                                    null,
                                    0,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
                }
                after = cursor.a();
            }
            var result = new ArrayList<CreatureSummary>();
            int at = Math.toIntExact(after + 1), looked = 0;
            for (;
                    at < readable.size() && result.size() < query.limit() && looked < maxScanRows;
                    at++) {
                looked++;
                var creature = readable.get(at);
                if (CreatureQueries.matches(creature.name(), query.query())) {
                    result.add(summary(creature));
                }
            }
            String next =
                    at >= readable.size()
                            ? null
                            : CreatureQueries.encodeCursor(binding, at - 1, "fake-creatures");
            return CompletableFuture.completedFuture(
                    new CreaturesPage(
                            CreaturesPageState.OK,
                            result,
                            next,
                            looked,
                            null,
                            null,
                            null,
                            null,
                            null));
        }

        private CreaturesPage invalid() {
            return new CreaturesPage(
                    CreaturesPageState.INVALID_CURSOR,
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    null,
                    "INVALID_CURSOR",
                    "Cursor differs from query");
        }

        @Override
        public CompletableFuture<CreatureResult> getData(
                long id, TableTypes.Priority priority, Cancellation token) {
            // Seeded fake details are already in memory; this never touches asset services.
            return get(id, priority, token);
        }

        @Override
        public CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
                TableTypes.Priority priority, Cancellation token) {
            enter("warmup", priority, token);
            return CompletableFuture.completedFuture(
                    new com.vibeminer.concepts.core.ConceptWarmup.Result(List.of()));
        }

        @Override
        public synchronized CompletableFuture<CreatureResult> get(
                long id, TableTypes.Priority priority, Cancellation token) {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("ID");
            }
            enter("get", priority, token);
            for (int i = 0; i < seed.creatures().size(); i++) {
                var c = seed.creatures().get(i);
                if (c.id() != id) {
                    continue;
                }
                if (c.outcome() != TableTypes.RowOutcome.FOUND) {
                    return CompletableFuture.completedFuture(
                            new CreatureResult(
                                    c.outcome() == TableTypes.RowOutcome.PROTECTED
                                            ? CreatureResultState.PROTECTED
                                            : CreatureResultState.UNKNOWN,
                                    null,
                                    null,
                                    c.protectedKey(),
                                    null,
                                    null));
                }
                var row =
                        c.row() == null
                                ? new TableTypes.Row(
                                        i,
                                        new Db2Types.Row(
                                                id,
                                                c.isCopy(),
                                                c.copySourceId(),
                                                List.of(Long.toString(id)),
                                                null))
                                : c.row();
                var chains =
                        c.displays() == null
                                ? slots(c).stream().map(s -> chain(s, seed.displays())).toList()
                                : c.displays();
                return CompletableFuture.completedFuture(
                        new CreatureResult(
                                CreatureResultState.FOUND,
                                new CreatureDetail(
                                        summary(c), row, c.classificationLabel(), chains),
                                null,
                                null,
                                null,
                                null));
            }
            return CompletableFuture.completedFuture(
                    new CreatureResult(CreatureResultState.ABSENT, null, null, null, null, null));
        }
    }
}
