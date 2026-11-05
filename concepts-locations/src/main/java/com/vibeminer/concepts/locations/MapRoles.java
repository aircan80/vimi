package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import com.vibeminer.questie.QuestieRepository;
import com.vibeminer.questie.QuestieTypes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service NPCs for map overlays from one prepared Questie snapshot: quest givers from explicit
 * {@code questStarts} relationships and vendors/trainers/flight masters from {@code npcFlags}.
 *
 * <p>The {@code npcFlags} bit layout differs per Questie flavor. Only the per-flavor layouts
 * reviewed from QuestieDB v1.0.10 {@code src/corrections/enum/expansions.lua} and the flavor/rules
 * map in {@code src/config.lua} are admitted; another release tag keeps the reviewed layout with an
 * explicit issue. Positions stay Questie AreaID percentages; conversion to a UiMap is the selected
 * build's job ({@link BuildLocationMaps#resolveAll}). Phase, faction reaction, quest availability
 * and service inventories are not evaluated.
 */
public final class MapRoles {
    public static final int MAX_NPCS = 65_536, MAX_LOCATIONS = LocationMapsService.MAX_BATCH;
    public static final String REVIEWED_RELEASE = "v1.0.10";
    static final String PROFILE = "questiedb-cbor-v2-4-schema-1";

    private MapRoles() {}

    public enum Layer {
        QUEST_GIVER,
        VENDOR,
        TRAINER,
        FLIGHT_MASTER
    }

    /**
     * Questie {@code friendlyToFaction}: "A", "H", "AH" (any case); a missing or unrecognised value
     * is UNKNOWN.
     */
    public enum Friendly {
        ALLIANCE,
        HORDE,
        BOTH,
        UNKNOWN
    }

    /** Reviewed {@code npcFlags} bits for one Questie flavor. */
    public record Layout(
            QuestieTypes.Flavor flavor, long vendor, long trainer, long flightMaster) {}

    /** One NPC with at least one admitted role and its recorded locations. */
    public record Npc(
            long id,
            String name,
            String subname,
            Set<Layer> layers,
            Friendly friendly,
            List<LocationTypes.Location> locations,
            LocationTypes.Evidence evidence) {
        public Npc {
            LocationTypes.validId(id, false);
            layers = Set.copyOf(layers);
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("A role NPC needs a role");
            }
            Objects.requireNonNull(friendly);
            locations = List.copyOf(locations);
            Objects.requireNonNull(evidence);
        }
    }

    public record Catalog(
            LocationTypes.State state,
            LocationTypes.Source source,
            String releaseTag,
            List<Npc> npcs,
            int locations,
            List<String> issues) {
        public Catalog {
            npcs = List.copyOf(npcs);
            issues = List.copyOf(issues);
        }
    }

    /**
     * QuestieDB v1.0.10: Vanilla uses rules "Classic"; Forever has its own table with the same low
     * bits; TBC, Wrath (Wotlk), Cata and Mists (MoP) share the later layout.
     */
    public static Optional<Layout> layout(QuestieTypes.Flavor flavor) {
        return Optional.of(
                switch (Objects.requireNonNull(flavor)) {
                    case VANILLA, FOREVER -> new Layout(flavor, 4, 16, 8);
                    case TBC, WRATH, CATA, MISTS -> new Layout(flavor, 128, 16, 8192);
                });
    }

    /** Local reads of one prepared Questie version; never downloads or prepares. */
    @SuppressWarnings("try")
    public static Catalog questie(
            QuestieRepository repository,
            String version,
            QuestieTypes.Flavor flavor,
            String locale,
            Cancellation token) {
        Objects.requireNonNull(repository);
        Objects.requireNonNull(flavor);
        var cancel = new com.vibeminer.generated.Cancellation();
        var rows = new ArrayList<Map<String, Object>>();
        var issues = new ArrayList<String>();
        String release;
        try (var registration = token.onCancel(cancel::cancel);
                var handle = repository.openVersion(version, cancel).join()) {
            if (!PROFILE.equals(handle.version().schemaProfile())) {
                throw new IllegalArgumentException("Questie role schema profile is unsupported");
            }
            if (handle.version().flavor() != flavor) {
                throw new IllegalArgumentException(
                        "Selected Questie flavor differs from prepared snapshot");
            }
            release = handle.version().releaseTag();
            for (int offset = 0; ; offset += 500) {
                token.check();
                var page =
                        handle.search(QuestieTypes.EntityKind.NPC, "", locale, offset, 500, cancel)
                                .join();
                boolean truncated = false;
                for (var record : page.entities()) {
                    if (rows.size() >= MAX_NPCS) {
                        truncated = true;
                        break;
                    }
                    var fields = new java.util.HashMap<String, Object>(record.fields());
                    fields.put("id", record.id());
                    rows.add(fields);
                }
                if (truncated
                        || rows.size() >= MAX_NPCS
                                && offset + page.entities().size() < page.total()) {
                    LocationAdapters.issue(issues, "Questie NPC bound reached; prefix retained.");
                    break;
                }
                if (offset + page.entities().size() >= page.total()) {
                    break;
                }
                if (page.entities().isEmpty()) {
                    LocationAdapters.issue(issues, "Questie NPC page is incomplete.");
                    break;
                }
            }
        }
        var source =
                new LocationTypes.Source(
                        LocationTypes.Provider.QUESTIE,
                        version,
                        flavor.upstream(),
                        null,
                        locale,
                        "See retained QuestieDB source notices");
        return fromRows(source, flavor, release, rows, issues, token);
    }

    /** Reviewed Questie NPC wire shapes; testable without a prepared database. */
    public static Catalog fromRows(
            LocationTypes.Source source,
            QuestieTypes.Flavor flavor,
            String releaseTag,
            List<Map<String, Object>> rows,
            List<String> readIssues,
            Cancellation token) {
        Objects.requireNonNull(source);
        if (source.provider() != LocationTypes.Provider.QUESTIE) {
            throw new IllegalArgumentException("Provider profile mismatch");
        }
        var layout = layout(flavor).orElseThrow();
        var issues = new ArrayList<>(readIssues);
        if (!REVIEWED_RELEASE.equals(releaseTag)) {
            LocationAdapters.issue(
                    issues,
                    "NPC flag layout was reviewed at QuestieDB "
                            + REVIEWED_RELEASE
                            + "; this snapshot is "
                            + (releaseTag == null ? "untagged" : releaseTag)
                            + ".");
        }
        var result = new ArrayList<Npc>();
        int locations = 0;
        int count = 0;
        for (var row : rows) {
            if ((++count & 1023) == 0) {
                token.check();
            }
            long id;
            try {
                id = LocationAdapters.integer(row.get("id"), false);
            } catch (IllegalArgumentException failure) {
                LocationAdapters.issue(issues, "Some Questie NPC IDs are unsupported.");
                continue;
            }
            var layers = EnumSet.noneOf(Layer.class);
            try {
                if (row.get("questStarts") != null) {
                    boolean unusable = false;
                    for (var entry : LocationAdapters.entries(row.get("questStarts"), 4096)) {
                        try {
                            LocationAdapters.integer(entry.getValue(), false);
                            layers.add(Layer.QUEST_GIVER);
                        } catch (IllegalArgumentException failure) {
                            unusable = true;
                        }
                    }
                    if (unusable) {
                        LocationAdapters.issue(
                                issues, "Some Questie questStarts entries are not quest IDs.");
                    }
                }
            } catch (IllegalArgumentException failure) {
                LocationAdapters.issue(issues, "Some Questie questStarts fields are unsupported.");
            }
            Object flagValue = row.get("npcFlags");
            if (flagValue != null) {
                try {
                    long flags = LocationAdapters.integer(flagValue, true);
                    if ((flags & layout.vendor()) != 0) {
                        layers.add(Layer.VENDOR);
                    }
                    if ((flags & layout.trainer()) != 0) {
                        layers.add(Layer.TRAINER);
                    }
                    if ((flags & layout.flightMaster()) != 0) {
                        layers.add(Layer.FLIGHT_MASTER);
                    }
                } catch (IllegalArgumentException failure) {
                    LocationAdapters.issue(issues, "Some Questie npcFlags are unsupported.");
                }
            }
            if (layers.isEmpty()) {
                continue;
            }
            if (locations >= MAX_LOCATIONS) {
                LocationAdapters.issue(issues, "Role location bound reached; prefix retained.");
                break;
            }
            var entity = new LocationTypes.Entity(LocationTypes.EntityKind.NPC, id);
            var parsed = LocationAdapters.questieLocations(entity, row, source);
            if (parsed.state() == LocationTypes.State.PARTIAL) {
                for (var issue : parsed.issues()) {
                    LocationAdapters.issue(issues, issue);
                }
            }
            var points = parsed.locations();
            if (locations + points.size() > MAX_LOCATIONS) {
                points = points.subList(0, MAX_LOCATIONS - locations);
                LocationAdapters.issue(issues, "Role location bound reached; prefix retained.");
            }
            locations += points.size();
            result.add(
                    new Npc(
                            id,
                            text(row.get("name")),
                            text(row.get("subName")),
                            layers,
                            friendly(row.get("friendlyToFaction")),
                            points,
                            new LocationTypes.Evidence(
                                    source, "NPC", Long.toString(id), "questStarts,npcFlags")));
        }
        return new Catalog(
                LocationAdapters.state(issues), source, releaseTag, result, locations, issues);
    }

    /**
     * One role NPC position on a selected-build UiMap, normalized to the map's 0..1 frame, with the
     * recorded Questie location it was placed from.
     */
    public record Marker(
            Npc npc, double x, double y, MapTypes.Basis basis, LocationTypes.Location location) {
        public Marker {
            Objects.requireNonNull(npc);
            Objects.requireNonNull(basis);
            Objects.requireNonNull(location);
        }
    }

    /**
     * Catalog positions resolved through the selected build's map relationships. A location is
     * drawn on a UiMap only when every drawable candidate for that UiMap agrees; otherwise it is
     * counted as ambiguous there and no preferred assignment is chosen.
     */
    public record Placement(
            Catalog catalog,
            Map<Long, List<Marker>> byUiMap,
            int locations,
            int unplaced,
            int ambiguous,
            List<String> issues) {
        public Placement {
            Objects.requireNonNull(catalog);
            var copy = new java.util.HashMap<Long, List<Marker>>();
            byUiMap.forEach((id, markers) -> copy.put(id, List.copyOf(markers)));
            byUiMap = Map.copyOf(copy);
            issues = List.copyOf(issues);
        }

        /** Markers on one UiMap carrying at least one of the given layers, in catalog order. */
        public List<Marker> markers(long uiMapId, Set<Layer> layers) {
            var found = byUiMap.getOrDefault(uiMapId, List.of());
            if (layers.containsAll(EnumSet.allOf(Layer.class))) {
                return found;
            }
            return found.stream()
                    .filter(m -> m.npc().layers().stream().anyMatch(layers::contains))
                    .toList();
        }
    }

    /** The catalog's recorded locations in NPC order: the input to one batch resolution. */
    public static List<LocationTypes.Location> locations(Catalog catalog) {
        var result = new ArrayList<LocationTypes.Location>(catalog.locations());
        for (var npc : catalog.npcs()) {
            result.addAll(npc.locations());
        }
        return List.copyOf(result);
    }

    /** Places resolutions aligned with {@link #locations(Catalog)}; misaligned input is refused. */
    public static Placement place(Catalog catalog, List<MapTypes.Resolution> resolutions) {
        Objects.requireNonNull(catalog);
        var expected = locations(catalog);
        if (resolutions.size() != expected.size()) {
            throw new IllegalArgumentException("Map resolutions do not match the role catalog");
        }
        var issues = new ArrayList<>(catalog.issues());
        var byUiMap = new java.util.HashMap<Long, List<Marker>>();
        int at = 0, unplaced = 0, ambiguous = 0;
        for (var npc : catalog.npcs()) {
            for (var location : npc.locations()) {
                var resolved = resolutions.get(at++);
                if (!location.equals(resolved.location())) {
                    throw new IllegalArgumentException(
                            "Map resolution order does not match the role catalog");
                }
                for (var issue : resolved.issues()) {
                    LocationAdapters.issue(issues, issue);
                }
                if (resolved.state() != MapTypes.State.READY && resolved.message() != null) {
                    LocationAdapters.issue(issues, resolved.message());
                }
                var groups = new java.util.LinkedHashMap<Long, List<MapTypes.Candidate>>();
                for (var candidate : resolved.candidates()) {
                    if (candidate.x() == null || candidate.y() == null) {
                        continue;
                    }
                    if (!(candidate.x() >= 0 && candidate.x() <= 1)
                            || !(candidate.y() >= 0 && candidate.y() <= 1)) {
                        LocationAdapters.issue(
                                issues,
                                "Some recorded positions fall outside the map frame; not drawn.");
                    } else {
                        groups.computeIfAbsent(candidate.map().id(), k -> new ArrayList<>())
                                .add(candidate);
                    }
                }
                boolean drawn = false;
                for (var entry : groups.entrySet()) {
                    var first = entry.getValue().getFirst();
                    boolean agree =
                            entry.getValue().stream()
                                    .allMatch(
                                            c ->
                                                    Math.abs(c.x() - first.x()) < 1e-6
                                                            && Math.abs(c.y() - first.y()) < 1e-6);
                    if (!agree) {
                        ambiguous++;
                        continue;
                    }
                    byUiMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                            .add(new Marker(npc, first.x(), first.y(), first.basis(), location));
                    drawn = true;
                }
                if (!drawn) {
                    unplaced++;
                }
            }
        }
        return new Placement(catalog, byUiMap, expected.size(), unplaced, ambiguous, issues);
    }

    /** Case and surrounding space are ignored; any other value is unrecognised (UNKNOWN). */
    static Friendly friendly(Object value) {
        if (!(value instanceof String raw) || raw.length() > 8) {
            return Friendly.UNKNOWN;
        }
        return switch (raw.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "A" -> Friendly.ALLIANCE;
            case "H" -> Friendly.HORDE;
            case "AH", "HA" -> Friendly.BOTH;
            default -> Friendly.UNKNOWN;
        };
    }

    private static String text(Object value) {
        return value instanceof String text && text.length() <= 512 ? text : null;
    }
}
