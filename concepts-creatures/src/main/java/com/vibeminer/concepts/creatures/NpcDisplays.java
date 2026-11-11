package com.vibeminer.concepts.creatures;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * The display an NPC page shows for one NPC: the client's {@code Creature} record first, then an
 * outside source's observed displays (EG Link) when the record is absent or declares none.
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
     * Link: the dataset, license, product, build and collection date).
     */
    public record External(String provenance, List<Candidate> candidates) {
        public External {
            Objects.requireNonNull(provenance);
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * The resolution: where the candidates came from, all of them (one per display ID, in shown
     * order), the one shown (null when none), the provenance and a readable detail.
     */
    public record Resolution(
            Source source,
            List<Candidate> candidates,
            Candidate shown,
            String provenance,
            String detail) {
        public Resolution {
            Objects.requireNonNull(source);
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(detail);
            if ((source == Source.NONE) != (shown == null)
                    || shown != null && !candidates.contains(shown)) {
                throw new IllegalArgumentException("Inconsistent display resolution");
            }
        }
    }

    /**
     * Resolves the display to show. The client record is used when it declares at least one
     * display; otherwise {@code external} (may be null) when it lists any.
     */
    public static Resolution resolve(
            CreatureResult client, String clientProvenance, External external) {
        Objects.requireNonNull(clientProvenance);
        String clientDetail = clientState(client);
        if (clientDetail == null) {
            var declared = new LinkedHashMap<Long, Candidate>();
            for (var slot : client.detail().summary().slots()) {
                if (slot.state() == CreatureSlotState.DECLARED && slot.displayId() > 0) {
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
            clientDetail = "The client Creature record declares no display";
        }
        if (external != null && !external.candidates().isEmpty()) {
            var listed = new LinkedHashMap<Long, Candidate>();
            for (var candidate : external.candidates()) {
                listed.merge(candidate.displayId(), candidate, NpcDisplays::sum);
            }
            return chosen(
                    Source.EXTERNAL,
                    listed.values(),
                    external.provenance(),
                    clientDetail + "; observed displays");
        }
        return new Resolution(
                Source.NONE,
                List.of(),
                null,
                clientProvenance,
                clientDetail
                        + (external == null
                                ? "; no outside display source applies"
                                : "; the outside source records no display for this NPC"));
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
