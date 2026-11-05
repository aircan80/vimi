package com.vibeminer.concepts.creatures;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Chooses one creature model for a placed creature only when the client data leaves no choice.
 *
 * <p>The input is the data-only creature detail of the opened build ({@code Creature} display
 * slots, then {@code CreatureDisplayInfo.ModelID}, then {@code CreatureModelData.FileDataID}). A
 * model is {@link State#QUALIFIED} only when exactly one distinct display is declared and that
 * display's chain resolves to a nonzero model file. Several declared displays are {@link
 * State#AMBIGUOUS}: the client picks among them at spawn time by probability, so no single model is
 * claimed. A single display whose chain stops is {@link State#UNRESOLVED}; a creature record that
 * cannot be read is {@link State#UNKNOWN}. Pure; performs no I/O.
 */
public final class CreatureModelPolicy {
    /** The policy identity shown with every outcome. */
    public static final String POLICY = "single-declared-display-v1";

    private CreatureModelPolicy() {}

    public enum State {
        QUALIFIED,
        AMBIGUOUS,
        UNRESOLVED,
        UNKNOWN
    }

    /** Short stable reason codes for per-reason counts. */
    public enum Reason {
        SINGLE_DISPLAY,
        SEVERAL_DISPLAYS,
        NO_DISPLAY,
        INVALID_DISPLAY,
        DISPLAY_UNREADABLE,
        DISPLAY_ABSENT,
        DISPLAY_PROTECTED,
        NO_MODEL,
        MODEL_UNREADABLE,
        MODEL_ABSENT,
        MODEL_PROTECTED,
        NO_MODEL_FILE,
        CREATURE_ABSENT,
        CREATURE_PROTECTED,
        CREATURE_UNREADABLE,
        NO_CREATURE_DATA
    }

    /**
     * One outcome. {@code displays} counts distinct declared display IDs (0 when unknown). The
     * display, model and file IDs are set as far as the chain resolved them; {@code
     * modelFileDataId} is set only when {@code state} is {@link State#QUALIFIED}.
     */
    public record Choice(
            State state,
            Reason reason,
            int displays,
            Integer displayId,
            Long modelId,
            Long modelFileDataId,
            String detail) {
        public Choice {
            Objects.requireNonNull(state);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
            if (displays < 0 || (state == State.QUALIFIED) != (modelFileDataId != null)) {
                throw new IllegalArgumentException("Inconsistent model choice");
            }
        }
    }

    /** No creature data could be consulted at all, for example while the build is not open. */
    public static Choice unknown(String detail) {
        return new Choice(State.UNKNOWN, Reason.NO_CREATURE_DATA, 0, null, null, null, detail);
    }

    /** Applies the policy to a data-only creature lookup ({@link BuildCreatures#getData}). */
    public static Choice choose(CreatureResult result) {
        if (result == null) {
            return unknown("No creature lookup result");
        }
        switch (result.state()) {
            case FOUND -> {}
            case ABSENT -> {
                return refusal(
                        State.UNKNOWN,
                        Reason.CREATURE_ABSENT,
                        "No Creature record for this NPC ID in the opened build");
            }
            case PROTECTED -> {
                return refusal(
                        State.UNKNOWN,
                        Reason.CREATURE_PROTECTED,
                        "The Creature record is encrypted"
                                + (result.protectedKey() == null
                                        ? ""
                                        : " (key " + result.protectedKey() + ")"));
            }
            default -> {
                return refusal(
                        State.UNKNOWN,
                        Reason.CREATURE_UNREADABLE,
                        "Creature table "
                                + switch (result.state()) {
                                    case CREATURE_NOT_READABLE -> "is not readable";
                                    case SCHEMA_UNSUPPORTED -> "layout is not supported";
                                    case SOURCE_INVALID -> "row failed validation";
                                    default -> "row outcome is unknown";
                                }
                                + (result.message() == null ? "" : ": " + result.message()));
            }
        }
        var detail = result.detail();
        if (detail == null
                || detail.summary() == null
                || detail.summary().slots() == null
                || detail.displays() == null) {
            return refusal(State.UNKNOWN, Reason.CREATURE_UNREADABLE, "Creature detail missing");
        }
        var declared = new TreeSet<Integer>();
        boolean invalid = false;
        for (var slot : detail.summary().slots()) {
            if (slot.state() == CreatureSlotState.NO_DECLARED_DISPLAY) {
                continue;
            }
            declared.add(slot.displayId());
            invalid |= slot.state() == CreatureSlotState.INVALID_DISPLAY_REFERENCE;
        }
        if (declared.isEmpty()) {
            return new Choice(
                    State.UNRESOLVED,
                    Reason.NO_DISPLAY,
                    0,
                    null,
                    null,
                    null,
                    "The Creature record declares no display");
        }
        if (declared.size() > 1) {
            return new Choice(
                    State.AMBIGUOUS,
                    Reason.SEVERAL_DISPLAYS,
                    declared.size(),
                    null,
                    null,
                    null,
                    declared.size()
                            + " displays declared "
                            + declared
                            + "; the client chooses one per spawn, so no single model is claimed");
        }
        int displayId = declared.first();
        if (invalid) {
            return new Choice(
                    State.UNRESOLVED,
                    Reason.INVALID_DISPLAY,
                    1,
                    displayId,
                    null,
                    null,
                    "The declared display ID " + displayId + " is not a valid reference");
        }
        CreatureDisplayChain chain = null;
        for (var candidate : detail.displays()) {
            if (candidate.slot() != null && candidate.slot().displayId() == displayId) {
                chain = candidate;
                break;
            }
        }
        if (chain == null || chain.state() == null) {
            return new Choice(
                    State.UNRESOLVED,
                    Reason.DISPLAY_UNREADABLE,
                    1,
                    displayId,
                    null,
                    null,
                    "No display chain was resolved for display " + displayId);
        }
        Long model = chain.modelId();
        if (chain.state() == CreatureDisplayChainState.METADATA_RESOLVED
                && chain.modelFileDataId() != null
                && chain.modelFileDataId() > 0) {
            return new Choice(
                    State.QUALIFIED,
                    Reason.SINGLE_DISPLAY,
                    1,
                    displayId,
                    model,
                    chain.modelFileDataId(),
                    "Single declared display "
                            + displayId
                            + " → CreatureModelData "
                            + model
                            + " → FileDataID "
                            + chain.modelFileDataId());
        }
        Reason reason =
                switch (chain.state()) {
                    case NO_DECLARED_DISPLAY -> Reason.NO_DISPLAY;
                    case INVALID_DISPLAY_REFERENCE -> Reason.INVALID_DISPLAY;
                    case DISPLAY_ABSENT -> Reason.DISPLAY_ABSENT;
                    case DISPLAY_PROTECTED -> Reason.DISPLAY_PROTECTED;
                    case DISPLAY_NOT_READABLE,
                            DISPLAY_UNSUPPORTED,
                            DISPLAY_UNKNOWN,
                            DISPLAY_INVALID ->
                            Reason.DISPLAY_UNREADABLE;
                    case NO_DECLARED_MODEL -> Reason.NO_MODEL;
                    case MODEL_ABSENT -> Reason.MODEL_ABSENT;
                    case MODEL_PROTECTED -> Reason.MODEL_PROTECTED;
                    case MODEL_NOT_READABLE, MODEL_UNSUPPORTED, MODEL_UNKNOWN, MODEL_INVALID ->
                            Reason.MODEL_UNREADABLE;
                    case NO_DECLARED_MODEL_FILE, METADATA_RESOLVED -> Reason.NO_MODEL_FILE;
                };
        return new Choice(
                State.UNRESOLVED,
                reason,
                1,
                displayId,
                model,
                null,
                "Display "
                        + displayId
                        + (model == null ? "" : " → CreatureModelData " + model)
                        + " stops: "
                        + describe(chain.state())
                        + (chain.message() == null ? "" : " (" + chain.message() + ")"));
    }

    /** Readable chain stop. */
    static String describe(CreatureDisplayChainState state) {
        return switch (state) {
            case NO_DECLARED_DISPLAY -> "no display declared";
            case INVALID_DISPLAY_REFERENCE -> "invalid display reference";
            case DISPLAY_NOT_READABLE -> "CreatureDisplayInfo is not readable";
            case DISPLAY_UNSUPPORTED -> "CreatureDisplayInfo layout is not supported";
            case DISPLAY_ABSENT -> "no CreatureDisplayInfo record";
            case DISPLAY_UNKNOWN -> "CreatureDisplayInfo row outcome unknown";
            case DISPLAY_PROTECTED -> "CreatureDisplayInfo record is encrypted";
            case DISPLAY_INVALID -> "CreatureDisplayInfo ModelID failed validation";
            case NO_DECLARED_MODEL -> "the display declares no model";
            case MODEL_NOT_READABLE -> "CreatureModelData is not readable";
            case MODEL_UNSUPPORTED -> "CreatureModelData layout is not supported";
            case MODEL_ABSENT -> "no CreatureModelData record";
            case MODEL_UNKNOWN -> "CreatureModelData row outcome unknown";
            case MODEL_PROTECTED -> "CreatureModelData record is encrypted";
            case MODEL_INVALID -> "CreatureModelData FileDataID failed validation";
            case NO_DECLARED_MODEL_FILE -> "the model declares no file";
            case METADATA_RESOLVED -> "the model file ID is not usable";
        };
    }

    private static Choice refusal(State state, Reason reason, String detail) {
        return new Choice(state, reason, 0, null, null, null, detail);
    }
}
