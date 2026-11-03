package com.vibeminer.concepts.dungeons;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded lazy dungeon metadata; optional relations never gate readable Map instance records. */
@SuppressWarnings("try")
public final class DungeonsService implements BuildDungeons {
    private record Loaded(DungeonTables.Data data, List<String> issues) {}

    private final DungeonTables tables;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("dungeon-projection").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Cancellation lifetime = new Cancellation();
    private final Map<String, Loaded> cache = new HashMap<>();
    private CompletableFuture<Void> closing;
    private DungeonTypes.Catalog catalog;

    public DungeonsService(BuildTables tables) {
        this.tables = new DungeonTables(Objects.requireNonNull(tables));
    }

    @Override
    public CompletableFuture<DungeonTypes.Catalog> catalog(Cancellation token) {
        return submit(token, this::initialize);
    }

    private DungeonTypes.Catalog initialize(Cancellation token) {
        if (catalog != null) {
            return catalog;
        }
        var issues = new ArrayList<String>();
        var sources = new ArrayList<TableTypes.Entry>();
        var result = new ArrayList<DungeonTypes.Dungeon>();
        var maps = read("Map", 10000, token, issues, sources);
        if (maps == null) {
            return new DungeonTypes.Catalog(
                    DungeonTypes.State.UNAVAILABLE,
                    List.of(),
                    sources,
                    issues,
                    "No readable instance map catalogue.");
        }
        try {
            maps.field("InstanceType", true, 1);
        } catch (IllegalArgumentException unsupported) {
            return new DungeonTypes.Catalog(
                    DungeonTypes.State.UNAVAILABLE,
                    List.of(),
                    sources,
                    issues,
                    "Map instance types are unsupported.");
        }
        for (var row : maps.rows()) {
            token.check();
            Long type = number(maps, row, "InstanceType", issues);
            if (type == null || type != 1 && type != 2) {
                continue;
            }
            String name = text(maps, row, "MapName_lang", issues);
            Long area = reference(maps, row, "AreaTableID", "AreaTable", issues);
            result.add(
                    new DungeonTypes.Dungeon(
                            row.id(),
                            name == null || name.isBlank() ? "Instance map #" + row.id() : name,
                            type == 1 ? DungeonTypes.Kind.DUNGEON : DungeonTypes.Kind.RAID,
                            positive(number(maps, row, "MaxPlayers", issues)),
                            positive(area),
                            number(maps, row, "ExpansionID", issues)));
        }
        result.sort(
                Comparator.comparing(DungeonTypes.Dungeon::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(DungeonTypes.Dungeon::mapId));
        token.check();
        catalog =
                new DungeonTypes.Catalog(
                        state(issues),
                        result,
                        sources,
                        issues,
                        result.isEmpty()
                                ? "No dungeon/raid records in the readable map rows."
                                : null);
        return catalog;
    }

    @Override
    public CompletableFuture<DungeonTypes.Overview> overview(long mapId, Cancellation token) {
        if (mapId < 0 || mapId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid map ID");
        }
        return submit(
                token,
                request -> {
                    var current = initialize(request);
                    var dungeon =
                            current.dungeons().stream()
                                    .filter(d -> d.mapId() == mapId)
                                    .findFirst()
                                    .orElse(null);
                    var issues = new ArrayList<>(current.issues());
                    var sources = new ArrayList<>(current.sources());
                    if (dungeon == null) {
                        return new DungeonTypes.Overview(
                                DungeonTypes.State.UNAVAILABLE,
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                sources,
                                issues,
                                "No readable dungeon/raid map with this ID.");
                    }
                    var descriptions = new ArrayList<DungeonTypes.Description>();
                    var maps = cache.get("Map").data();
                    var map =
                            maps.rows().stream()
                                    .filter(r -> r.id() == mapId)
                                    .findFirst()
                                    .orElseThrow();
                    description(
                            descriptions,
                            maps,
                            map,
                            "MapDescription0_lang",
                            "Horde briefing",
                            issues);
                    description(
                            descriptions,
                            maps,
                            map,
                            "MapDescription1_lang",
                            "Alliance briefing",
                            issues);
                    var journal = read("JournalInstance", 10000, request, issues, sources);
                    if (journal != null) {
                        try {
                            journal.reference("MapID", "Map");
                            for (var row : journal.rows()) {
                                request.check();
                                if (journal.number(row, "MapID") == mapId) {
                                    description(
                                            descriptions,
                                            journal,
                                            row,
                                            "Description_lang",
                                            "Journal description",
                                            issues);
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            DungeonTables.note(
                                    issues, "Journal map relationships are unavailable.");
                        }
                    }
                    var names = new HashMap<Long, String>();
                    var difficulties = read("Difficulty", 1000, request, issues, sources);
                    if (difficulties != null) {
                        for (var row : difficulties.rows()) {
                            String name = text(difficulties, row, "Name_lang", issues);
                            if (name != null && !name.isBlank()) {
                                names.put(row.id(), name);
                            }
                        }
                    }
                    var variants = new ArrayList<DungeonTypes.Difficulty>();
                    var mapDifficulties = read("MapDifficulty", 50000, request, issues, sources);
                    if (mapDifficulties != null) {
                        try {
                            mapDifficulties.reference("MapID", "Map");
                            mapDifficulties.reference("DifficultyID", "Difficulty");
                            for (var row : mapDifficulties.rows()) {
                                request.check();
                                try {
                                    if (mapDifficulties.number(row, "MapID") != mapId) {
                                        continue;
                                    }
                                    long id = mapDifficulties.number(row, "DifficultyID");
                                    if (variants.size() >= 256) {
                                        DungeonTables.note(
                                                issues,
                                                "Additional difficulty records exceed the 256-record overview bound.");
                                        break;
                                    }
                                    variants.add(
                                            new DungeonTypes.Difficulty(
                                                    row.id(),
                                                    id,
                                                    names.getOrDefault(id, "Difficulty #" + id),
                                                    positive(
                                                            number(
                                                                    mapDifficulties,
                                                                    row,
                                                                    "MaxPlayers",
                                                                    issues)),
                                                    text(
                                                            mapDifficulties,
                                                            row,
                                                            "Message_lang",
                                                            issues)));
                                } catch (IllegalArgumentException invalid) {
                                    DungeonTables.note(
                                            issues, "Some difficulty rows are unreadable.");
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            DungeonTables.note(
                                    issues, "Map difficulty relationships are unavailable.");
                        }
                    }
                    variants.sort(
                            Comparator.comparingLong(DungeonTypes.Difficulty::difficultyId)
                                    .thenComparingLong(DungeonTypes.Difficulty::recordId));
                    var queues = new ArrayList<DungeonTypes.Queue>();
                    var lfg = read("LFGDungeons", 20000, request, issues, sources);
                    if (lfg != null) {
                        try {
                            lfg.reference("MapID", "Map");
                            for (var row : lfg.rows()) {
                                request.check();
                                try {
                                    if (lfg.number(row, "MapID") != mapId) {
                                        continue;
                                    }
                                    if (queues.size() >= 128) {
                                        DungeonTables.note(
                                                issues,
                                                "Additional queue records exceed the 128-record overview bound.");
                                        break;
                                    }
                                    queues.add(
                                            new DungeonTypes.Queue(
                                                    row.id(),
                                                    text(lfg, row, "Name_lang", issues),
                                                    reference(
                                                            lfg,
                                                            row,
                                                            "DifficultyID",
                                                            "Difficulty",
                                                            issues),
                                                    reference(
                                                            lfg,
                                                            row,
                                                            "ContentTuningID",
                                                            "ContentTuning",
                                                            issues),
                                                    positive(number(lfg, row, "MinLevel", issues)),
                                                    positive(
                                                            number(lfg, row, "MaxLevel", issues))));
                                } catch (IllegalArgumentException invalid) {
                                    DungeonTables.note(
                                            issues, "Some queue records are unreadable.");
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            DungeonTables.note(
                                    issues, "Queue-to-map relationships are unavailable.");
                        }
                    }
                    queues.sort(Comparator.comparingLong(DungeonTypes.Queue::recordId));
                    var encounters = new ArrayList<DungeonTypes.Encounter>();
                    var encounterData = read("DungeonEncounter", 50000, request, issues, sources);
                    if (encounterData != null) {
                        try {
                            encounterData.reference("MapID", "Map");
                            for (var row : encounterData.rows()) {
                                request.check();
                                try {
                                    if (encounterData.number(row, "MapID") != mapId) {
                                        continue;
                                    }
                                    Long difficulty =
                                            reference(
                                                    encounterData,
                                                    row,
                                                    "DifficultyID",
                                                    "Difficulty",
                                                    issues);
                                    String name = text(encounterData, row, "Name_lang", issues);
                                    if (encounters.size() >= 2048) {
                                        DungeonTables.note(
                                                issues,
                                                "Additional encounter records exceed the 2048-record overview bound.");
                                        break;
                                    }
                                    encounters.add(
                                            new DungeonTypes.Encounter(
                                                    row.id(),
                                                    name == null || name.isBlank()
                                                            ? "Encounter #" + row.id()
                                                            : name,
                                                    difficulty,
                                                    difficulty == null
                                                            ? null
                                                            : names.getOrDefault(
                                                                    difficulty,
                                                                    "Difficulty #" + difficulty),
                                                    number(
                                                            encounterData,
                                                            row,
                                                            "OrderIndex",
                                                            issues),
                                                    positive(
                                                            reference(
                                                                    encounterData,
                                                                    row,
                                                                    "CreatureDisplayID",
                                                                    "CreatureDisplayInfo",
                                                                    issues)),
                                                    positive(
                                                            reference(
                                                                    encounterData,
                                                                    row,
                                                                    "SpellIconFileID",
                                                                    "FileData",
                                                                    issues))));
                                } catch (IllegalArgumentException invalid) {
                                    DungeonTables.note(
                                            issues, "Some encounter rows are unreadable.");
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            DungeonTables.note(
                                    issues, "Encounter-to-map relationships are unavailable.");
                        }
                    }
                    encounters.sort(
                            Comparator.comparing(
                                            DungeonTypes.Encounter::difficultyId,
                                            Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(
                                            DungeonTypes.Encounter::order,
                                            Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparingLong(DungeonTypes.Encounter::id));
                    var areas = areas(dungeon.areaId(), request, issues, sources);
                    return new DungeonTypes.Overview(
                            state(issues),
                            dungeon,
                            areas,
                            descriptions,
                            variants,
                            queues,
                            encounters,
                            sources,
                            issues,
                            null);
                });
    }

    private List<DungeonTypes.Area> areas(
            Long areaId, Cancellation token, List<String> issues, List<TableTypes.Entry> sources) {
        if (areaId == null) {
            return List.of();
        }
        var result = new ArrayList<DungeonTypes.Area>();
        DungeonTables.Data data;
        try {
            data = tables.schema("AreaTable", token, issues);
            addSource(sources, data.entry());
        } catch (IllegalArgumentException unavailable) {
            DungeonTables.note(issues, "Associated area data unavailable.");
            return List.of();
        }
        var seen = new HashSet<Long>();
        Long id = areaId;
        while (id != null && id > 0) {
            token.check();
            if (result.size() >= 16 || !seen.add(id)) {
                DungeonTables.note(
                        issues, "Associated area hierarchy is cyclic or exceeds its bound.");
                break;
            }
            var row = tables.get(data, id, token, issues);
            if (row == null) {
                break;
            }
            Long parent = reference(data, row, "ParentAreaID", "AreaTable", issues);
            result.add(
                    new DungeonTypes.Area(
                            id,
                            text(data, row, "AreaName_lang", issues),
                            parent,
                            reference(data, row, "ContinentID", "Map", issues)));
            id = parent;
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    private DungeonTables.Data read(
            String name,
            int maximum,
            Cancellation token,
            List<String> issues,
            List<TableTypes.Entry> sources) {
        var saved = cache.get(name);
        if (saved != null) {
            saved.issues().forEach(i -> DungeonTables.note(issues, i));
            if (saved.data() != null) {
                addSource(sources, saved.data().entry());
            }
            return saved.data();
        }
        var notes = new ArrayList<String>();
        DungeonTables.Data data;
        try {
            data = tables.read(name, maximum, token, notes);
        } catch (IllegalArgumentException unavailable) {
            DungeonTables.note(notes, name + " unavailable: " + unavailable.getMessage());
            data = null;
        }
        token.check();
        cache.put(name, new Loaded(data, List.copyOf(notes)));
        notes.forEach(i -> DungeonTables.note(issues, i));
        if (data != null) {
            addSource(sources, data.entry());
        }
        return data;
    }

    private static void addSource(List<TableTypes.Entry> sources, TableTypes.Entry source) {
        if (sources.stream().noneMatch(s -> s.fileDataId() == source.fileDataId())) {
            sources.add(source);
        }
    }

    private static DungeonTypes.State state(List<String> issues) {
        return issues.isEmpty() ? DungeonTypes.State.READY : DungeonTypes.State.PARTIAL;
    }

    private static String text(
            DungeonTables.Data data, Db2Types.Row row, String field, List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            return data.text(row, field);
        } catch (IllegalArgumentException unsupported) {
            DungeonTables.note(issues, data.name() + "." + field + " is unavailable.");
            return null;
        }
    }

    private static Long number(
            DungeonTables.Data data, Db2Types.Row row, String field, List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            return data.number(row, field);
        } catch (IllegalArgumentException unsupported) {
            DungeonTables.note(issues, data.name() + "." + field + " is unavailable.");
            return null;
        }
    }

    private static Long reference(
            DungeonTables.Data data,
            Db2Types.Row row,
            String field,
            String target,
            List<String> issues) {
        if (!data.has(field)) {
            return null;
        }
        try {
            data.reference(field, target);
            return data.number(row, field);
        } catch (IllegalArgumentException unsupported) {
            DungeonTables.note(issues, data.name() + "." + field + " reference is unavailable.");
            return null;
        }
    }

    private static Long positive(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static void description(
            List<DungeonTypes.Description> result,
            DungeonTables.Data data,
            Db2Types.Row row,
            String field,
            String title,
            List<String> issues) {
        String value = text(data, row, field, issues);
        if (value != null && !value.isBlank()) {
            if (result.size() >= 64) {
                DungeonTables.note(
                        issues, "Additional descriptions exceed the 64-record overview bound.");
                return;
            }
            result.add(new DungeonTypes.Description(title, value, data.name(), row.id(), field));
        }
    }

    private <T> CompletableFuture<T> submit(Cancellation token, Function<Cancellation, T> action) {
        Objects.requireNonNull(token);
        var result = new CompletableFuture<T>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Dungeon handle closed"));
            }
            try {
                worker.execute(
                        () -> {
                            var request = new Cancellation();
                            try (var caller = token.onCancel(request::cancel);
                                    var owner = lifetime.onCancel(request::cancel)) {
                                request.check();
                                result.complete(action.apply(request));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                result.completeExceptionally(
                        new IllegalStateException("Dungeon queries busy", full));
            }
        }
        var published = new CompletableFuture<T>();
        result.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        published.complete(value);
                                    } else {
                                        published.completeExceptionally(failure);
                                    }
                                }));
        return published;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable cancelFailure = null;
        try {
            lifetime.cancel();
        } catch (Throwable failure) {
            cancelFailure = failure;
        }
        final Throwable failure = cancelFailure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        if (failure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(failure);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }
}
