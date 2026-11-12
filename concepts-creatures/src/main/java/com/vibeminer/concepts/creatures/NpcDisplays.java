package com.vibeminer.concepts.creatures;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongPredicate;

/**
 * The display an NPC page shows for one NPC: the client's {@code Creature} record first, then each
 * outside source's observed displays in order (EG Link: downloaded snapshots, newest build first,
 * then the bundled history) when the record is absent or declares none. A display the opened
 * build's {@code CreatureDisplayInfo} proves missing is skipped, so its source falls through.
 *
 * <p>Every candidate display is listed with its probability (weights of a display listed several
 * times add up). The one shown is the highest probability, ties broken by the lowest display ID, so
 * the same data always shows the same display. This is a presentation choice for one preview: the
 * World keeps {@link CreatureModelPolicy} (a single declared display only). Pure; performs no I/O.
 */
public final class NpcDisplays {
    /** The rule identity shown with every resolution. */
    public static final String RULE = "highest-probability-lowest-id-v1";

    private NpcDisplays() {}

    public enum Source {
        CLIENT,
        EXTERNAL,
        NONE
    }

    /** One candidate display: its ID, probability, and scale when the source records one. */
    public record Candidate(long displayId, double probability, Double scale) {
        public Candidate {
            if (displayId < 1
                    || displayId > 0xffffffffL
                    || !Double.isFinite(probability)
                    || probability < 0) {
                throw new IllegalArgumentException("Invalid display candidate");
            }
        }
    }

    /**
     * One outside source's displays for the NPC, with the provenance shown beside them (for EG
     * Link: the dataset, license, product, build and collection date). {@code problem} is set when
     * the source records the NPC but its displays could not be read (a malformed record); such a
     * source lists no candidate and is stated as malformed, never as recording no display.
     */
    public record External(String provenance, List<Candidate> candidates, String problem) {
        public External {
            Objects.requireNonNull(provenance);
            candidates = List.copyOf(candidates);
            if (problem != null && !candidates.isEmpty()) {
                throw new IllegalArgumentException("A malformed record lists no display");
            }
        }

        public External(String provenance, List<Candidate> candidates) {
            this(provenance, candidates, null);
        }

        public boolean malformed() {
            return problem != null;
        }
    }

    /**
     * The resolution: where the candidates came from, all of them (one per display ID, in shown
     * order), the one shown (null when none), the provenance and a readable detail. {@code
     * malformed} counts the outside sources tried whose record for the NPC is malformed.
     */
    public record Resolution(
            Source source,
            List<Candidate> candidates,
            Candidate shown,
            String provenance,
            String detail,
            int malformed) {
        public Resolution {
            Objects.requireNonNull(source);
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(detail);
            if ((source == Source.NONE) != (shown == null)
                    || shown != null && !candidates.contains(shown)
                    || malformed < 0) {
                throw new IllegalArgumentException("Inconsistent display resolution");
            }
        }

        public Resolution(
                Source source,
                List<Candidate> candidates,
                Candidate shown,
                String provenance,
                String detail) {
            this(source, candidates, shown, provenance, detail, 0);
        }
    }

    /**
     * Resolves the display to show. The client record is used when it declares at least one
     * display; otherwise {@code external} (may be null) when it lists any.
     */
    public static Resolution resolve(
            CreatureResult client, String clientProvenance, External external) {
        return resolve(
                client, clientProvenance, external == null ? null : List.of(external), id -> false);
    }

    /**
     * Resolves the display to show from the client record, then each outside source in order
     * ({@code externals}, null when none applies). A display {@code absent} proves missing from the
     * opened build's {@code CreatureDisplayInfo} is never used: a source left with no display falls
     * through to the next one. A display that cannot be checked (a partial table) is kept.
     */
    public static Resolution resolve(
            CreatureResult client,
            String clientProvenance,
            List<External> externals,
            LongPredicate absent) {
        Objects.requireNonNull(clientProvenance);
        Objects.requireNonNull(absent);
        String clientDetail = clientState(client);
        if (clientDetail == null) {
            var declared = new LinkedHashMap<Long, Candidate>();
            int missing = 0;
            for (var slot : client.detail().summary().slots()) {
                if (slot.state() == CreatureSlotState.DECLARED && slot.displayId() > 0) {
                    if (absent.test(slot.displayId())) {
                        missing++;
                        continue;
                    }
                    declared.merge(
                            (long) slot.displayId(),
                            new Candidate(
                                    slot.displayId(),
                                    Float.isFinite(slot.probability()) && slot.probability() >= 0
                                            ? slot.probability()
                                            : 0,
                                    null),
                            NpcDisplays::sum);
                }
            }
            if (!declared.isEmpty()) {
                return chosen(
                        Source.CLIENT, declared.values(), clientProvenance, "client Creature");
            }
            clientDetail =
                    missing > 0
                            ? "The client Creature record's displays are not in this build's"
                                    + " CreatureDisplayInfo"
                            : "The client Creature record declares no display";
        }
        int passed = 0, malformed = 0;
        String problem = null;
        for (var external : externals == null ? List.<External>of() : externals) {
            var listed = new LinkedHashMap<Long, Candidate>();
            for (var candidate : external.candidates()) {
                if (!absent.test(candidate.displayId())) {
                    listed.merge(candidate.displayId(), candidate, NpcDisplays::sum);
                }
            }
            if (!listed.isEmpty()) {
                var resolution =
                        chosen(
                                Source.EXTERNAL,
                                listed.values(),
                                external.provenance(),
                                clientDetail
                                        + "; observed displays"
                                        + (passed == 0
                                                ? ""
                                                : passed == 1
                                                        ? " (one newer source had none in this"
                                                                + " build)"
                                                        : " ("
                                                                + passed
                                                                + " newer sources had none in this"
                                                                + " build)")
                                        + malformed(malformed, problem));
                return new Resolution(
                        resolution.source(),
                        resolution.candidates(),
                        resolution.shown(),
                        resolution.provenance(),
                        resolution.detail(),
                        malformed);
            }
            passed++;
            if (external.malformed()) {
                malformed++;
                if (problem == null) {
                    problem = external.provenance() + ": " + external.problem();
                }
            }
        }
        int sources = externals == null ? 0 : externals.size();
        return new Resolution(
                Source.NONE,
                List.of(),
                null,
                clientProvenance,
                clientDetail
                        + (sources == 0
                                ? "; no outside display source applies"
                                : malformed == sources
                                        ? "; the outside "
                                                + (malformed == 1 ? "source's record" : "records")
                                                + " for this NPC "
                                                + (malformed == 1 ? "is" : "are")
                                                + " malformed"
                                        : sources == 1
                                                ? "; the outside source records no display for"
                                                        + " this NPC"
                                                : "; no outside source records a display for this"
                                                        + " NPC in this build")
                        + malformed(malformed == sources ? 0 : malformed, null)
                        + (problem == null ? "" : " (" + problem + ")"),
                malformed);
    }

    /** The detail's note of malformed records passed over, or nothing. */
    private static String malformed(int count, String problem) {
        if (count == 0) {
            return "";
        }
        return (count == 1
                        ? "; one outside record for this NPC is malformed"
                        : "; " + count + " outside records for this NPC are malformed")
                + (problem == null ? "" : " (" + problem + ")");
    }

    /** Null when the client record can supply displays, else why not. */
    private static String clientState(CreatureResult client) {
        if (client == null) {
            return "Client creature data is not open";
        }
        if (client.state() == CreatureResultState.ABSENT) {
            return "No client Creature record for this NPC";
        }
        if (client.state() != CreatureResultState.FOUND
                || client.detail() == null
                || client.detail().summary() == null
                || client.detail().summary().slots() == null) {
            return "The client Creature record is " + client.state();
        }
        return null;
    }

    /** One display listed twice: its weights add up, as the client's spawn choice would. */
    private static Candidate sum(Candidate left, Candidate right) {
        return new Candidate(
                left.displayId(),
                left.probability() + right.probability(),
                left.scale() != null ? left.scale() : right.scale());
    }

    private static Resolution chosen(
            Source source,
            java.util.Collection<Candidate> candidates,
            String provenance,
            String origin) {
        var ordered = new ArrayList<>(candidates);
        ordered.sort(
                Comparator.comparingDouble(Candidate::probability)
                        .reversed()
                        .thenComparingLong(Candidate::displayId));
        var shown = ordered.getFirst();
        return new Resolution(
                source,
                ordered,
                shown,
                provenance,
                ordered.size() == 1
                        ? "Display " + shown.displayId() + " (" + origin + ")"
                        : "Display "
                                + shown.displayId()
                                + " of "
                                + ordered.size()
                                + " ("
                                + origin
                                + "; highest probability, then lowest ID)");
    }
}
