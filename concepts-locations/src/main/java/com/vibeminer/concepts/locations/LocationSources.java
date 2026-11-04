package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import com.vibeminer.eglink.EgLinkRepository;
import com.vibeminer.eglink.EgLinkTypes;
import com.vibeminer.polestar.PolestarRepository;
import com.vibeminer.polestar.PolestarTypes;
import com.vibeminer.questie.QuestieRepository;
import com.vibeminer.questie.QuestieTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Existing prepared-snapshot boundaries only: no refresh, download, prepare or provider SQL here.
 */
@SuppressWarnings("try")
public final class LocationSources {
    private LocationSources() {}

    public static LocationInput questie(
            QuestieRepository repository, String version, String sourceFlavor, String locale) {
        var flavor = QuestieTypes.Flavor.parse(sourceFlavor);
        var source =
                new LocationTypes.Source(
                        LocationTypes.Provider.QUESTIE,
                        version,
                        flavor.upstream(),
                        null,
                        locale,
                        "See retained QuestieDB source notices");
        return new LocationInput(
                source,
                new LocationInput.Reader() {
                    @Override
                    public LocationTypes.Locations locations(
                            LocationTypes.Entity entity, Cancellation token) {
                        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
                            return LocationAdapters.item(entity);
                        }
                        var cancel = new com.vibeminer.generated.Cancellation();
                        try (var registration = token.onCancel(cancel::cancel);
                                var handle = repository.openVersion(version, cancel).join()) {
                            if (!"questiedb-cbor-v2-4-schema-1"
                                    .equals(handle.version().schemaProfile())) {
                                throw new IllegalArgumentException(
                                        "Questie coordinate/starter schema profile is unsupported");
                            }
                            if (handle.version().flavor() != flavor) {
                                throw new IllegalArgumentException(
                                        "Selected Questie flavor differs from prepared snapshot");
                            }
                            var row =
                                    handle.lookup(kind(entity.kind()), entity.id(), locale, cancel)
                                            .join();
                            return LocationAdapters.questieLocations(
                                    entity, row.map(r -> r.fields()).orElse(null), source);
                        }
                    }

                    @Override
                    public LocationTypes.Starters starters(long questId, Cancellation token) {
                        var cancel = new com.vibeminer.generated.Cancellation();
                        try (var registration = token.onCancel(cancel::cancel);
                                var handle = repository.openVersion(version, cancel).join()) {
                            if (!"questiedb-cbor-v2-4-schema-1"
                                    .equals(handle.version().schemaProfile())) {
                                throw new IllegalArgumentException(
                                        "Questie coordinate/starter schema profile is unsupported");
                            }
                            if (handle.version().flavor() != flavor) {
                                throw new IllegalArgumentException(
                                        "Selected Questie flavor differs from prepared snapshot");
                            }
                            var row =
                                    handle.lookup(
                                                    QuestieTypes.EntityKind.QUEST,
                                                    questId,
                                                    locale,
                                                    cancel)
                                            .join();
                            return LocationAdapters.questieStarters(
                                    questId, row.map(r -> r.fields()).orElse(null), source);
                        }
                    }
                });
    }

    private static QuestieTypes.EntityKind kind(LocationTypes.EntityKind kind) {
        return switch (kind) {
            case NPC -> QuestieTypes.EntityKind.NPC;
            case OBJECT -> QuestieTypes.EntityKind.OBJECT;
            case ITEM -> QuestieTypes.EntityKind.ITEM;
        };
    }

    public static LocationInput polestar(
            PolestarRepository repository, PolestarTypes.Snapshot snapshot) {
        var source = polestarSource(snapshot);
        return new LocationInput(
                source,
                new LocationInput.Reader() {
                    private Map<Long, List<Map<String, Object>>> starters;
                    private List<String> starterIssues;
                    private LocationTypes.Frames frames;

                    @Override
                    public LocationTypes.Locations locations(
                            LocationTypes.Entity entity, Cancellation token) {
                        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
                            return LocationAdapters.item(entity);
                        }
                        var cancel = new com.vibeminer.network.Cancellation();
                        var rows = new ArrayList<Map<String, Object>>();
                        var issues = new ArrayList<String>();
                        try (var registration = token.onCancel(cancel::cancel)) {
                            for (int offset = 0; ; offset += 500) {
                                token.check();
                                var page =
                                        repository
                                                .lookupPage(
                                                        snapshot.id(),
                                                        PolestarTypes.Table.SPAWN,
                                                        Long.toString(entity.id()),
                                                        offset,
                                                        500,
                                                        cancel)
                                                .join();
                                for (var row : page.records()) {
                                    if (rows.size() >= 16_384) {
                                        LocationAdapters.issue(
                                                issues, "Polestar entity-row bound reached.");
                                        break;
                                    }
                                    rows.add(row.fields());
                                }
                                if (rows.size() >= 16_384
                                        || offset + page.records().size() >= page.total()) {
                                    break;
                                }
                                if (page.records().isEmpty()) {
                                    LocationAdapters.issue(
                                            issues, "Polestar spawn page is incomplete.");
                                    break;
                                }
                            }
                        } catch (RuntimeException failure) {
                            token.check();
                            LocationAdapters.issue(
                                    issues,
                                    "Polestar spawn read is incomplete; readable prefix retained.");
                        }
                        var parsed = LocationAdapters.polestarLocations(entity, rows, source);
                        issues.addAll(parsed.issues());
                        return new LocationTypes.Locations(
                                LocationAdapters.state(issues), entity, parsed.locations(), issues);
                    }

                    @Override
                    public synchronized LocationTypes.Starters starters(
                            long questId, Cancellation token) {
                        if (starters == null) {
                            var batch =
                                    all(
                                            repository,
                                            snapshot.id(),
                                            PolestarTypes.Table.QUEST_START,
                                            100_000,
                                            token);
                            var issues = new ArrayList<>(batch.issues());
                            var index = new HashMap<Long, List<Map<String, Object>>>();
                            for (var row : batch.rows()) {
                                try {
                                    long quest = LocationAdapters.integer(row.get("quest"), false);
                                    var group =
                                            index.computeIfAbsent(
                                                    quest, ignored -> new ArrayList<>());
                                    if (group.size() < LocationAdapters.MAX_STARTERS) {
                                        group.add(row);
                                    } else {
                                        LocationAdapters.issue(
                                                issues,
                                                "Some Polestar starter groups exceed bounds.");
                                    }
                                } catch (IllegalArgumentException failure) {
                                    LocationAdapters.issue(
                                            issues,
                                            "Some Polestar quest-start rows are unsupported.");
                                }
                            }
                            token.check();
                            starters = index;
                            starterIssues = List.copyOf(issues);
                        }
                        var parsed =
                                LocationAdapters.polestarStarters(
                                        questId, starters.getOrDefault(questId, List.of()), source);
                        var issues = new ArrayList<>(starterIssues);
                        issues.addAll(parsed.issues());
                        return new LocationTypes.Starters(
                                LocationAdapters.state(issues), questId, parsed.starters(), issues);
                    }

                    @Override
                    public synchronized LocationTypes.Frames frames(Cancellation token) {
                        if (frames == null) {
                            var batch =
                                    all(
                                            repository,
                                            snapshot.id(),
                                            PolestarTypes.Table.UI_MAP,
                                            8192,
                                            token);
                            var parsed = LocationAdapters.polestarFrames(batch.rows(), source);
                            var issues = new ArrayList<>(batch.issues());
                            issues.addAll(parsed.issues());
                            var result =
                                    new LocationTypes.Frames(
                                            LocationAdapters.state(issues),
                                            parsed.frames(),
                                            issues);
                            token.check();
                            frames = result;
                        }
                        return frames;
                    }
                });
    }

    static LocationTypes.Source polestarSource(PolestarTypes.Snapshot snapshot) {
        return new LocationTypes.Source(
                LocationTypes.Provider.POLESTAR,
                snapshot.id(),
                "Forever",
                snapshot.catalog().clientBuild(),
                "enUS",
                "GPL-3.0-only");
    }

    /** Bounded local read of every prepared row in one table; readable prefix on failure. */
    static Rows allRows(
            PolestarRepository repository,
            String snapshot,
            PolestarTypes.Table table,
            int bound,
            Cancellation token) {
        return all(repository, snapshot, table, bound, token);
    }

    record Rows(List<Map<String, Object>> rows, List<String> issues) {
        Rows {
            rows = List.copyOf(rows);
            issues = List.copyOf(issues);
        }
    }

    private static Rows all(
            PolestarRepository repository,
            String snapshot,
            PolestarTypes.Table table,
            int bound,
            Cancellation token) {
        var result = new ArrayList<Map<String, Object>>();
        var issues = new ArrayList<String>();
        var cancel = new com.vibeminer.network.Cancellation();
        try (var registration = token.onCancel(cancel::cancel)) {
            for (int offset = 0; ; offset += 500) {
                token.check();
                var page = repository.search(snapshot, table, "", offset, 500, cancel).join();
                for (var row : page.records()) {
                    if (result.size() >= bound) {
                        LocationAdapters.issue(
                                issues,
                                "Prepared location table bound reached; readable prefix retained.");
                        break;
                    }
                    result.add(row.fields());
                }
                if (offset + page.records().size() >= page.total()) {
                    break;
                }
                if (result.size() >= bound) {
                    LocationAdapters.issue(
                            issues,
                            "Prepared location table bound reached; readable prefix retained.");
                    break;
                }
                if (page.records().isEmpty()) {
                    LocationAdapters.issue(
                            issues,
                            "Prepared location page is incomplete; readable prefix retained.");
                    break;
                }
            }
        } catch (RuntimeException failure) {
            token.check();
            LocationAdapters.issue(
                    issues,
                    "Prepared location table read is incomplete; readable prefix retained.");
        }
        return new Rows(result, issues);
    }

    public static LocationInput egLink(EgLinkRepository repository, EgLinkTypes.Snapshot snapshot) {
        var source =
                new LocationTypes.Source(
                        LocationTypes.Provider.EG_LINK,
                        snapshot.id(),
                        snapshot.build().product(),
                        snapshot.build().version(),
                        "",
                        EgLinkTypes.LICENSE);
        boolean hasNpcs =
                snapshot.build().datasets().stream().anyMatch(d -> d.name().equals("npcs"));
        return new LocationInput(
                source,
                new LocationInput.Reader() {
                    private Map<Long, List<LocationTypes.Starter>> starters;
                    private List<String> starterIssues;

                    @Override
                    public LocationTypes.Locations locations(
                            LocationTypes.Entity entity, Cancellation token) throws Exception {
                        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
                            return LocationAdapters.item(entity);
                        }
                        if (!hasNpcs) {
                            return LocationAdapters.unknown(
                                    entity, "The selected EG snapshot has no npcs dataset.");
                        }
                        var cancel = new com.vibeminer.network.Cancellation();
                        try (var registration = token.onCancel(cancel::cancel)) {
                            var row =
                                    repository
                                            .lookup(
                                                    snapshot.id(),
                                                    "npcs",
                                                    collection(entity.kind()),
                                                    Long.toString(entity.id()),
                                                    cancel)
                                            .join();
                            return LocationAdapters.egLocations(
                                    entity,
                                    row.isEmpty() ? null : LocationJson.parse(row.get().json()),
                                    source);
                        }
                    }

                    @Override
                    public synchronized LocationTypes.Starters starters(
                            long questId, Cancellation token) throws Exception {
                        if (!hasNpcs) {
                            return new LocationTypes.Starters(
                                    LocationTypes.State.UNKNOWN,
                                    questId,
                                    List.of(),
                                    List.of("The selected EG snapshot has no npcs dataset."));
                        }
                        if (starters == null) {
                            var index = new HashMap<Long, List<LocationTypes.Starter>>();
                            var issues = new ArrayList<String>();
                            var cancel = new com.vibeminer.network.Cancellation();
                            int totalOffers = 0;
                            try (var registration = token.onCancel(cancel::cancel)) {
                                scan:
                                for (var kind :
                                        List.of(
                                                LocationTypes.EntityKind.NPC,
                                                LocationTypes.EntityKind.OBJECT)) {
                                    for (int offset = 0; ; offset += 200) {
                                        token.check();
                                        if (offset >= 100_000) {
                                            LocationAdapters.issue(
                                                    issues,
                                                    "EG interaction table bound reached; readable prefix retained.");
                                            break;
                                        }
                                        var page =
                                                repository
                                                        .search(
                                                                snapshot.id(),
                                                                "npcs",
                                                                collection(kind),
                                                                "",
                                                                offset,
                                                                200,
                                                                cancel)
                                                        .join();
                                        for (var row : page.records()) {
                                            try {
                                                long id = Long.parseLong(row.id());
                                                var entity = new LocationTypes.Entity(kind, id);
                                                var offers =
                                                        LocationAdapters.egOffers(
                                                                entity,
                                                                LocationJson.parse(row.json()),
                                                                source,
                                                                issues);
                                                for (var offer : offers) {
                                                    if (totalOffers++ >= 100_000) {
                                                        LocationAdapters.issue(
                                                                issues,
                                                                "EG starter index bound reached; readable prefix retained.");
                                                        break scan;
                                                    }
                                                    var group =
                                                            index.computeIfAbsent(
                                                                    offer.questId(),
                                                                    ignored -> new ArrayList<>());
                                                    if (group.size()
                                                            < LocationAdapters.MAX_STARTERS) {
                                                        group.add(offer);
                                                    } else {
                                                        LocationAdapters.issue(
                                                                issues,
                                                                "Some EG offered-quest groups exceed bounds.");
                                                    }
                                                }
                                            } catch (java.io.IOException
                                                    | IllegalArgumentException failure) {
                                                LocationAdapters.issue(
                                                        issues,
                                                        "Some EG offered-quest records are unsupported.");
                                            }
                                        }
                                        if (!page.more()) {
                                            break;
                                        }
                                        if (page.records().isEmpty()) {
                                            LocationAdapters.issue(
                                                    issues,
                                                    "EG interaction page is incomplete; readable prefix retained.");
                                            break;
                                        }
                                    }
                                }
                            } catch (RuntimeException failure) {
                                token.check();
                                LocationAdapters.issue(
                                        issues,
                                        "EG starter read is incomplete; readable prefix retained.");
                            }
                            token.check();
                            starters = index;
                            starterIssues = List.copyOf(issues);
                        }
                        return new LocationTypes.Starters(
                                LocationAdapters.state(starterIssues),
                                questId,
                                starters.getOrDefault(questId, List.of()),
                                starterIssues);
                    }
                });
    }

    private static String collection(LocationTypes.EntityKind kind) {
        return switch (kind) {
            case NPC -> "npcs";
            case OBJECT -> "objects";
            case ITEM ->
                    throw new IllegalArgumentException("Items have no EG interaction collection");
        };
    }
}
