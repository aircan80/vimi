package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason;
import com.vibeminer.concepts.creatures.CreatureModelPolicy.State;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDetail;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplaySlot;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSummary;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreatureModelPolicyTest {
    @Test
    void singleDeclaredDisplayWithResolvedFileQualifies() {
        var choice =
                CreatureModelPolicy.choose(
                        found(
                                new int[] {1234, 0, 0, 0},
                                chain(
                                        1234,
                                        CreatureDisplayChainState.METADATA_RESOLVED,
                                        77L,
                                        123456L)));
        assertEquals(State.QUALIFIED, choice.state());
        assertEquals(Reason.SINGLE_DISPLAY, choice.reason());
        assertEquals(1, choice.displays());
        assertEquals(1234, choice.displayId());
        assertEquals(77L, choice.modelId());
        assertEquals(123456L, choice.modelFileDataId());
        assertTrue(choice.detail().contains("FileDataID 123456"));
    }

    @Test
    void repeatedSlotsOfOneDisplayStillQualify() {
        var resolved = chain(9, CreatureDisplayChainState.METADATA_RESOLVED, 3L, 42L);
        var choice = CreatureModelPolicy.choose(found(new int[] {9, 0, 9, 0}, resolved, resolved));
        assertEquals(State.QUALIFIED, choice.state());
        assertEquals(42L, choice.modelFileDataId());
    }

    @Test
    void severalDisplaysAreAmbiguousWithTheirCount() {
        var choice =
                CreatureModelPolicy.choose(
                        found(
                                new int[] {11, 12, 0, 13},
                                chain(11, CreatureDisplayChainState.METADATA_RESOLVED, 1L, 5L),
                                chain(12, CreatureDisplayChainState.METADATA_RESOLVED, 2L, 6L),
                                chain(13, CreatureDisplayChainState.METADATA_RESOLVED, 3L, 7L)));
        assertEquals(State.AMBIGUOUS, choice.state());
        assertEquals(Reason.SEVERAL_DISPLAYS, choice.reason());
        assertEquals(3, choice.displays());
        assertNull(choice.modelFileDataId());
        assertTrue(choice.detail().startsWith("3 displays declared [11, 12, 13]"));
    }

    @Test
    void noDeclaredDisplayIsUnresolved() {
        var choice = CreatureModelPolicy.choose(found(new int[] {0, 0, 0, 0}));
        assertEquals(State.UNRESOLVED, choice.state());
        assertEquals(Reason.NO_DISPLAY, choice.reason());
        assertEquals(0, choice.displays());
    }

    @Test
    void invalidDisplayReferenceIsUnresolved() {
        var choice =
                CreatureModelPolicy.choose(
                        found(
                                new int[] {-5, 0, 0, 0},
                                chain(
                                        -5,
                                        CreatureDisplayChainState.INVALID_DISPLAY_REFERENCE,
                                        null,
                                        null)));
        assertEquals(State.UNRESOLVED, choice.state());
        assertEquals(Reason.INVALID_DISPLAY, choice.reason());
    }

    @Test
    void brokenSingleChainsAreUnresolvedWithTheirStop() {
        record Case(CreatureDisplayChainState state, Long model, Reason reason) {}
        for (var c :
                List.of(
                        new Case(
                                CreatureDisplayChainState.DISPLAY_ABSENT,
                                null,
                                Reason.DISPLAY_ABSENT),
                        new Case(
                                CreatureDisplayChainState.DISPLAY_PROTECTED,
                                null,
                                Reason.DISPLAY_PROTECTED),
                        new Case(
                                CreatureDisplayChainState.DISPLAY_UNSUPPORTED,
                                null,
                                Reason.DISPLAY_UNREADABLE),
                        new Case(CreatureDisplayChainState.NO_DECLARED_MODEL, 0L, Reason.NO_MODEL),
                        new Case(CreatureDisplayChainState.MODEL_ABSENT, 8L, Reason.MODEL_ABSENT),
                        new Case(
                                CreatureDisplayChainState.MODEL_PROTECTED,
                                8L,
                                Reason.MODEL_PROTECTED),
                        new Case(
                                CreatureDisplayChainState.MODEL_INVALID,
                                8L,
                                Reason.MODEL_UNREADABLE),
                        new Case(
                                CreatureDisplayChainState.NO_DECLARED_MODEL_FILE,
                                8L,
                                Reason.NO_MODEL_FILE))) {
            var choice =
                    CreatureModelPolicy.choose(
                            found(new int[] {4, 0, 0, 0}, chain(4, c.state(), c.model(), null)));
            assertEquals(State.UNRESOLVED, choice.state(), c.toString());
            assertEquals(c.reason(), choice.reason(), c.toString());
            assertEquals(4, choice.displayId());
            assertNull(choice.modelFileDataId());
            assertTrue(choice.detail().contains("stops: "), choice.detail());
        }
    }

    @Test
    void resolvedChainWithoutUsableFileIsNotQualified() {
        var choice =
                CreatureModelPolicy.choose(
                        found(
                                new int[] {4, 0, 0, 0},
                                chain(4, CreatureDisplayChainState.METADATA_RESOLVED, 8L, 0L)));
        assertEquals(State.UNRESOLVED, choice.state());
        assertEquals(Reason.NO_MODEL_FILE, choice.reason());
    }

    @Test
    void unreadableCreatureRecordsAreUnknown() {
        assertEquals(
                Reason.CREATURE_ABSENT,
                CreatureModelPolicy.choose(result(CreatureResultState.ABSENT)).reason());
        var protectedChoice =
                CreatureModelPolicy.choose(
                        new CreatureResult(
                                CreatureResultState.PROTECTED, null, null, "ABCD", null, null));
        assertEquals(State.UNKNOWN, protectedChoice.state());
        assertEquals(Reason.CREATURE_PROTECTED, protectedChoice.reason());
        assertTrue(protectedChoice.detail().contains("ABCD"));
        for (var state :
                List.of(
                        CreatureResultState.UNKNOWN,
                        CreatureResultState.CREATURE_NOT_READABLE,
                        CreatureResultState.SCHEMA_UNSUPPORTED,
                        CreatureResultState.SOURCE_INVALID)) {
            var choice = CreatureModelPolicy.choose(result(state));
            assertEquals(State.UNKNOWN, choice.state(), state.toString());
            assertEquals(Reason.CREATURE_UNREADABLE, choice.reason(), state.toString());
        }
        assertEquals(State.UNKNOWN, CreatureModelPolicy.choose(null).state());
        assertEquals(
                Reason.CREATURE_UNREADABLE,
                CreatureModelPolicy.choose(result(CreatureResultState.FOUND)).reason());
        assertEquals(Reason.NO_CREATURE_DATA, CreatureModelPolicy.unknown("closed").reason());
    }

    @Test
    void qualifiedChoicesAlwaysCarryAFile() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CreatureModelPolicy.Choice(
                                State.QUALIFIED, Reason.SINGLE_DISPLAY, 1, 1, 1L, null, "x"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CreatureModelPolicy.Choice(
                                State.AMBIGUOUS, Reason.SEVERAL_DISPLAYS, 2, null, null, 5L, "x"));
    }

    private static CreatureResult result(CreatureResultState state) {
        return new CreatureResult(state, null, null, null, null, null);
    }

    private static CreatureResult found(int[] displays, CreatureDisplayChain... chains) {
        var slots = new ArrayList<CreatureDisplaySlot>();
        for (int slot = 0; slot < displays.length; slot++) {
            int id = displays[slot];
            slots.add(
                    new CreatureDisplaySlot(
                            slot,
                            id,
                            id == 0 ? 0 : 1,
                            id > 0
                                    ? CreatureSlotState.DECLARED
                                    : id == 0
                                            ? CreatureSlotState.NO_DECLARED_DISPLAY
                                            : CreatureSlotState.INVALID_DISPLAY_REFERENCE));
        }
        var summary =
                new CreatureSummary(
                        100,
                        false,
                        null,
                        new CreatureName(CreatureNameState.VALID, "Test", null),
                        List.of(),
                        slots);
        return new CreatureResult(
                CreatureResultState.FOUND,
                new CreatureDetail(summary, null, null, List.of(chains)),
                null,
                null,
                null,
                null);
    }

    private static CreatureDisplayChain chain(
            int displayId, CreatureDisplayChainState state, Long model, Long file) {
        return new CreatureDisplayChain(
                new CreatureDisplaySlot(0, displayId, 1, CreatureSlotState.DECLARED),
                state,
                null,
                null,
                model,
                null,
                null,
                file,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
