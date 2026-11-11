package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.NpcDisplays;
import com.vibeminer.concepts.models.BuildDisplayModels;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves the model preview of one NPC for the NPCs page: its display ({@link NpcDisplays}: the
 * client Creature record, then EG Link's observed displays), that display's model, and, for a
 * humanoid display, the race appearance the World uses. Where a humanoid appearance is refused,
 * the display's own (untextured) geometry is shown with the reason, never nothing. Not FX; one
 * call per selection.
 */
final class NpcPreviews {
    /** The outside display lookup: an NPC's observed displays, or empty. */
    @FunctionalInterface
    interface Observed {
        Optional<NpcDisplays.External> displays(long npcId, Cancellation token) throws Exception;
    }

    enum Kind {
        NONE,
        CREATURE,
        HUMANOID
    }

    /**
     * One NPC's preview. {@code appearance} is what is drawn (null when nothing is); {@code
     * textured} says whether any surface carries a texture; {@code reason} says why a model is
     * untextured or missing (null when textured); {@code facts} are the lines shown with it.
     */
    record Outcome(
            long npcId,
            NpcDisplays.Resolution resolution,
            Kind kind,
            MeshAppearance appearance,
            Set<Integer> visibleParts,
            boolean textured,
            String reason,
            List<String> facts) {
        Outcome {
            Objects.requireNonNull(resolution);
            Objects.requireNonNull(kind);
            visibleParts = Set.copyOf(visibleParts);
            facts = List.copyOf(facts);
        }

        /** The display whose idle may play: a creature display that is drawn, else null. */
        Long idleDisplay() {
            return kind == Kind.CREATURE && appearance != null && resolution.shown() != null
                    ? resolution.shown().displayId()
                    : null;
        }
    }

    private final Supplier<BuildCreatures> creatures;
    private final Supplier<BuildDisplayModels> displays;
    private final Supplier<BuildRaces> races;
    private final Observed observed;
    private final String build;

    NpcPreviews(
            Supplier<BuildCreatures> creatures,
            Supplier<BuildDisplayModels> displays,
            Supplier<BuildRaces> races,
            Observed observed,
            String build) {
        this.creatures = Objects.requireNonNull(creatures);
        this.displays = Objects.requireNonNull(displays);
        this.races = Objects.requireNonNull(races);
        this.observed = Objects.requireNonNull(observed);
        this.build = build == null || build.isBlank() ? "unknown" : build;
    }

    Outcome resolve(long npcId, Cancellation token) throws Exception {
        if (npcId < 0 || npcId > 0xffffffffL) {
            throw new IllegalArgumentException("NPC ID is uint32");
        }
        var handle = creatures.get();
        var client =
                handle == null
                        ? null
                        : handle.getData(npcId, TableTypes.Priority.INTERACTIVE, token).join();
        token.check();
        var external = observed.displays(npcId, token).orElse(null);
        token.check();
        var resolution =
                NpcDisplays.resolve(client, "client Creature record, build " + build, external);
        var facts = new ArrayList<String>();
        facts.add(resolution.detail());
        double total =
                resolution.candidates().stream()
                        .mapToDouble(NpcDisplays.Candidate::probability)
                        .sum();
        for (var candidate : resolution.candidates()) {
            facts.add(
                    "Display "
                            + candidate.displayId()
                            + " · weight "
                            + String.format(Locale.ROOT, "%.4g", candidate.probability())
                            + (total > 0
                                    ? String.format(
                                            Locale.ROOT,
                                            " (%.0f%%)",
                                            100 * candidate.probability() / total)
                                    : "")
                            + (candidate == resolution.shown() ? " · shown" : ""));
        }
        facts.add("Source: " + resolution.provenance());
        if (resolution.shown() == null) {
            return new Outcome(
                    npcId, resolution, Kind.NONE, null, Set.of(), false, resolution.detail(), facts);
        }
        var models = displays.get();
        if (models == null) {
            return new Outcome(
                    npcId,
                    resolution,
                    Kind.NONE,
                    null,
                    Set.of(),
                    false,
                    "Display models are not open for this build",
                    facts);
        }
        long displayId = resolution.shown().displayId();
        var preview = models.preview(displayId, token).join();
        token.check();
        Long extended =
                preview == null || preview.reference() == null
                        ? null
                        : preview.reference().extendedDisplayInfoId();
        if (extended != null && extended > 0) {
            return humanoid(npcId, resolution, preview, extended, facts, token);
        }
        if (preview == null || preview.appearance() == null) {
            String reason =
                    preview == null || preview.message() == null
                            ? "The display model is unavailable"
                            : preview.message();
            return new Outcome(npcId, resolution, Kind.CREATURE, null, Set.of(), false, reason, facts);
        }
        boolean textured = !preview.appearance().surfaces().isEmpty();
        facts.addAll(preview.issues());
        return new Outcome(
                npcId,
                resolution,
                Kind.CREATURE,
                preview.appearance(),
                preview.visibleParts(),
                textured,
                textured ? null : "No surface of this model has a reviewed texture binding",
                facts);
    }

    private Outcome humanoid(
            long npcId,
            NpcDisplays.Resolution resolution,
            DisplayModelTypes.Preview preview,
            long extended,
            List<String> facts,
            Cancellation token)
            throws Exception {
        facts.add("Humanoid display · extended display info " + extended);
        var handle = races.get();
        var answer =
                handle == null
                        ? com.vibeminer.concepts.races.RaceNpcs.Appearance.refused(
                                "race data is not open")
                        : handle.npcAppearance(extended, token).join();
        token.check();
        if (answer.result() != null && answer.result().appearance() != null) {
            facts.addAll(answer.result().issues());
            return new Outcome(
                    npcId,
                    resolution,
                    Kind.HUMANOID,
                    answer.result().appearance(),
                    answer.result().visibleParts(),
                    true,
                    null,
                    facts);
        }
        String refusal =
                answer.refusal() != null
                        ? answer.refusal()
                        : answer.result() != null && answer.result().message() != null
                                ? answer.result().message()
                                : "the character appearance is unavailable";
        String reason = "Character appearance not shown: " + refusal;
        if (preview.appearance() == null) {
            return new Outcome(
                    npcId, resolution, Kind.HUMANOID, null, Set.of(), false, reason, facts);
        }
        // The display's own model geometry, without its character textures.
        var untextured =
                new MeshAppearance(
                        preview.appearance().mesh(),
                        preview.appearance().uv(),
                        preview.appearance().normals(),
                        List.of(),
                        List.of());
        facts.add(
                "Untextured character model; surfaces follow the default character convention,"
                        + " not the NPC's recorded choices.");
        return new Outcome(
                npcId,
                resolution,
                Kind.HUMANOID,
                untextured,
                defaultCharacterParts(untextured.mesh()),
                false,
                reason,
                facts);
    }

    /**
     * The character surfaces a race preview shows before any choice: the body ({@code 0}), each
     * group's first value ({@code x01}) and group 32, without groups 12, 17 and 35 (the races
     * page's reviewed default convention).
     */
    static Set<Integer> defaultCharacterParts(com.vibeminer.rendering.models.PreviewMesh mesh) {
        var visible = new java.util.HashSet<Integer>();
        for (var part : mesh.parts()) {
            int id = part.id(), group = id / 100;
            if ((id == 0 || id % 100 == 1 || group == 32)
                    && group != 17
                    && group != 35
                    && group != 12) {
                visible.add(id);
            }
        }
        return visible;
    }

    /** The EG Link lookup of the NPCs page: its display fallback snapshot, read locally. */
    @SuppressWarnings("try")
    static Observed egLink(DataSourcesService sources, BuildInfo build, String locale) {
        return (npcId, token) -> {
            if (sources == null) return Optional.empty();
            var snapshot = sources.npcDisplaySnapshot(build, locale);
            if (snapshot == null) return Optional.empty();
            var network = new com.vibeminer.network.Cancellation();
            try (var registration = token.onCancel(network::cancel)) {
                var recorded = sources.npcDisplays(build, locale, npcId, network);
                if (recorded.isEmpty()) {
                    // The snapshot applies but records no such creature: said so, not hidden.
                    return Optional.of(
                            new NpcDisplays.External(
                                    "EG Link creature_details ("
                                            + com.vibeminer.eglink.EgLinkTypes.LICENSE
                                            + "), "
                                            + snapshot.build().product()
                                            + " build "
                                            + snapshot.build().version(),
                                    List.of()));
                }
                return recorded
                        .map(
                                displays ->
                                        new NpcDisplays.External(
                                                displays.provenance(),
                                                displays.displays().stream()
                                                        .map(
                                                                d ->
                                                                        new NpcDisplays.Candidate(
                                                                                d.displayId(),
                                                                                d.probability(),
                                                                                d.scale()))
                                                        .toList()));
            }
        };
    }
}
