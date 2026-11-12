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
    /**
     * The outside display lookup: an NPC's observed displays from each applicable source, in the
     * order they are tried (empty when no source applies). A source that applies but records no
     * display for the NPC is listed with no candidates.
     */
    @FunctionalInterface
    interface Observed {
        List<NpcDisplays.External> displays(long npcId, Cancellation token) throws Exception;
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
            List<String> facts,
            long extendedDisplayInfoId) {
        Outcome {
            Objects.requireNonNull(resolution);
            Objects.requireNonNull(kind);
            visibleParts = Set.copyOf(visibleParts);
            facts = List.copyOf(facts);
        }

        Outcome(
                long npcId,
                NpcDisplays.Resolution resolution,
                Kind kind,
                MeshAppearance appearance,
                Set<Integer> visibleParts,
                boolean textured,
                String reason,
                List<String> facts) {
            this(npcId, resolution, kind, appearance, visibleParts, textured, reason, facts, 0);
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
        String clientProvenance = "client Creature record, build " + build;
        var facts = new ArrayList<String>();
        var absent = absentDisplays(handle, token);
        // The client first: the outside sources are read only when the client supplies no display,
        // so their failure never hides an NPC the client describes.
        var resolution = NpcDisplays.resolve(client, clientProvenance, null, absent);
        if (resolution.source() != NpcDisplays.Source.CLIENT) {
            List<NpcDisplays.External> externals = null;
            try {
                externals = observed.displays(npcId, token);
            } catch (java.util.concurrent.CancellationException canceled) {
                throw canceled;
            } catch (Exception failure) {
                token.check();
                facts.add("Observed displays could not be read: " + rootMessage(failure));
            }
            token.check();
            resolution = NpcDisplays.resolve(client, clientProvenance, externals, absent);
        }
        facts.add(0, resolution.detail());
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
        if (handle != null) {
            try {
                var chain =
                        handle.displayChain(displayId, TableTypes.Priority.INTERACTIVE, token)
                                .join();
                facts.add(
                        "Display "
                                + displayId
                                + (chain.modelId() == null
                                        ? ""
                                        : " → CreatureModelData " + chain.modelId())
                                + (chain.modelFileDataId() == null
                                        ? " · " + chain.state()
                                        : " → FileDataID " + chain.modelFileDataId()));
            } catch (RuntimeException unavailable) {
                token.check();
                facts.add("The display's model chain could not be read: " + rootMessage(unavailable));
            }
        }
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
                    facts,
                    extended);
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
                com.vibeminer.concepts.races.RaceMediaTypes.defaultCharacterParts(
                        untextured.mesh()),
                false,
                reason,
                facts);
    }


    /**
     * Whether the opened build's CreatureDisplayInfo proves a display missing (absent, or an ID no
     * slot can hold). A display that cannot be checked, a partial table or a chain that fails, is
     * not missing. Remembers each answer for this resolution.
     */
    static java.util.function.LongPredicate absentDisplays(
            BuildCreatures handle, Cancellation token) {
        var known = new java.util.HashMap<Long, Boolean>();
        return displayId -> {
            if (handle == null) return false;
            if (displayId < 1 || displayId > Integer.MAX_VALUE) return true;
            return known.computeIfAbsent(
                    displayId,
                    id -> {
                        try {
                            var state =
                                    handle.displayChain(id, TableTypes.Priority.INTERACTIVE, token)
                                            .join()
                                            .state();
                            return state
                                            == com.vibeminer.concepts.creatures.CreatureTypes
                                                    .CreatureDisplayChainState.DISPLAY_ABSENT
                                    || state
                                            == com.vibeminer.concepts.creatures.CreatureTypes
                                                    .CreatureDisplayChainState
                                                    .INVALID_DISPLAY_REFERENCE;
                        } catch (RuntimeException unavailable) {
                            token.check();
                            return false;
                        }
                    });
        };
    }

    /** An idle for the preview: the animation, or why there is none. */
    record Idle(com.vibeminer.rendering.models.PreviewAnimation animation, String message) {}

    /**
     * The Stand idle of a drawn outcome: the display's idle for a creature, the race model's Stand
     * fitted to the NPC's appearance (its attached items following their bones) for a humanoid.
     */
    Idle idle(Outcome outcome, Cancellation token) throws Exception {
        if (outcome.appearance() == null) {
            return new Idle(null, null);
        }
        if (outcome.kind() == Kind.CREATURE && outcome.idleDisplay() != null) {
            var models = displays.get();
            if (models == null) return new Idle(null, null);
            var idle = models.idle(outcome.idleDisplay(), token).join();
            return switch (idle.state()) {
                case AVAILABLE -> new Idle(idle.animation(), idle.message());
                case UNSUPPORTED -> new Idle(null, "Idle animation unavailable: " + idle.message());
                case UNAVAILABLE -> new Idle(null, null);
            };
        }
        if (outcome.kind() == Kind.HUMANOID && outcome.extendedDisplayInfoId() > 0) {
            var handle = races.get();
            if (handle == null) return new Idle(null, null);
            var idle =
                    handle.npcIdle(outcome.extendedDisplayInfoId(), outcome.appearance(), token)
                            .join();
            if (idle.animation() != null && idle.animation().matches(outcome.appearance())) {
                return new Idle(idle.animation(), idle.message());
            }
            return new Idle(
                    null,
                    idle.message() == null ? null : "Idle animation unavailable: " + idle.message());
        }
        return new Idle(
                null,
                outcome.kind() == Kind.HUMANOID
                        ? "Idle animation is not evaluated for an untextured character model."
                        : null);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * The EG Link lookups of the NPCs page, read locally, in order: every prepared downloaded
     * snapshot of the opened product and x.y.z not after the opened build (newest build first,
     * then newest commit), then the bundled history's entries of the same product and x.y.z in
     * {@link #bundledOrder}: the newest at or below the opened build first, then newer ones, which
     * are still offered so no NPC is missed; {@link NpcDisplays} drops any display the opened
     * build's CreatureDisplayInfo proves missing.
     */
    @SuppressWarnings("try")
    static Observed egLink(DataSourcesService sources, BuildInfo build, String locale) {
        return egLink(
                sources == null ? List::of : () -> sources.npcDisplaySnapshots(build, locale),
                (snapshot, npcId, token) -> sources.npcDisplays(snapshot, locale, npcId, token),
                com.vibeminer.eglink.BundledNpcHistory::bundled,
                build);
    }

    /** One downloaded snapshot's lookup. */
    @FunctionalInterface
    interface SnapshotLookup {
        Optional<com.vibeminer.eglink.EgLinkTypes.CreatureDisplays> displays(
                com.vibeminer.eglink.EgLinkTypes.Snapshot snapshot,
                long npcId,
                com.vibeminer.network.Cancellation token)
                throws Exception;
    }

    @SuppressWarnings("try")
    static Observed egLink(
            Supplier<List<com.vibeminer.eglink.EgLinkTypes.Snapshot>> snapshots,
            SnapshotLookup lookup,
            Supplier<com.vibeminer.eglink.BundledNpcHistory> bundled,
            BuildInfo build) {
        return (npcId, token) -> {
            var result = new ArrayList<NpcDisplays.External>();
            var network = new com.vibeminer.network.Cancellation();
            try (var registration = token.onCancel(network::cancel)) {
                // Chosen once: each source's provenance and displays come from the same snapshot.
                for (var snapshot : snapshots.get()) {
                    token.check();
                    String provenance =
                            "EG Link creature_details ("
                                    + com.vibeminer.eglink.EgLinkTypes.LICENSE
                                    + "), "
                                    + snapshot.build().product()
                                    + " build "
                                    + snapshot.build().version()
                                    + ", commit "
                                    + snapshot.build().revision().substring(0, 12);
                    Optional<com.vibeminer.eglink.EgLinkTypes.CreatureDisplays> recorded;
                    try {
                        recorded = lookup.displays(snapshot, npcId, network);
                    } catch (java.util.concurrent.CancellationException canceled) {
                        throw canceled;
                    } catch (Exception failure) {
                        // One unreadable snapshot (pruned meanwhile, say) leaves the others.
                        token.check();
                        result.add(
                                new NpcDisplays.External(
                                        provenance
                                                + " (could not be read: "
                                                + rootMessage(failure)
                                                + ")",
                                        List.of()));
                        continue;
                    }
                    result.add(
                            new NpcDisplays.External(
                                    recorded.map(d -> provenance + ", collected " + d.collectedOn())
                                            .orElse(provenance),
                                    recorded.map(NpcPreviews::candidates).orElse(List.of())));
                }
            }
            var history = bundled.get();
            if (history != null && build != null && history.product().equals(build.product())) {
                for (var entry : bundledOrder(history.entries(npcId), build.version())) {
                    result.add(
                            new NpcDisplays.External(
                                    entry.provenance(),
                                    entry.displays().stream().map(NpcPreviews::candidate).toList()));
                }
            }
            return List.copyOf(result);
        };
    }

    /**
     * The bundled entries of one NPC offered for {@code version}, in order: those of the same x.y.z
     * at or below it, newest first, then the newer ones, newest first ("latest wins, don't miss
     * NPCs"). {@code entries} come newest build first.
     */
    static List<com.vibeminer.eglink.BundledNpcHistory.Entry> bundledOrder(
            List<com.vibeminer.eglink.BundledNpcHistory.Entry> entries, String version) {
        var atOrBelow = new ArrayList<com.vibeminer.eglink.BundledNpcHistory.Entry>();
        var newer = new ArrayList<com.vibeminer.eglink.BundledNpcHistory.Entry>();
        for (var entry : entries) {
            String built = entry.source().build();
            if (!sameRelease(built, version)) continue;
            (DataSourcesService.compatibleNpcBuild(built, version) ? atOrBelow : newer).add(entry);
        }
        atOrBelow.addAll(newer);
        return List.copyOf(atOrBelow);
    }

    /** Whether two builds share their x.y.z. */
    static boolean sameRelease(String a, String b) {
        String pattern = "\\d+\\.\\d+\\.\\d+\\.\\d+";
        return a != null
                && b != null
                && a.matches(pattern)
                && b.matches(pattern)
                && a.substring(0, a.lastIndexOf('.')).equals(b.substring(0, b.lastIndexOf('.')));
    }

    private static List<NpcDisplays.Candidate> candidates(
            com.vibeminer.eglink.EgLinkTypes.CreatureDisplays displays) {
        return displays.displays().stream().map(NpcPreviews::candidate).toList();
    }

    private static NpcDisplays.Candidate candidate(
            com.vibeminer.eglink.EgLinkTypes.CreatureDisplay display) {
        return new NpcDisplays.Candidate(display.displayId(), display.probability(), display.scale());
    }
}
