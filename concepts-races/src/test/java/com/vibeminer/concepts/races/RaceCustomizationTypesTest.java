package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RaceCustomizationTypesTest {
    @Test
    void refusesOverCapacityBeforeAccessingCallerElements() {
        var picks =
                new AbstractList<RaceCustomizationTypes.Pick>() {
                    @Override
                    public int size() {
                        return 17;
                    }

                    @Override
                    public RaceCustomizationTypes.Pick get(int index) {
                        throw new AssertionError("Count must be admitted before copying");
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () -> new RaceCustomizationTypes.Request(3, 1, 5, picks));
        var choices =
                new AbstractList<RaceCustomizationTypes.Choice>() {
                    @Override
                    public int size() {
                        return 65;
                    }

                    @Override
                    public RaceCustomizationTypes.Choice get(int index) {
                        throw new AssertionError("Count must be admitted before copying");
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () -> new RaceCustomizationTypes.Option(30, "Skin Color", choices, 524));
    }

    @Test
    void requestsDetachAndRejectDuplicateOptionsOrInvalidContext() {
        var picks = new ArrayList<>(List.of(new RaceCustomizationTypes.Pick(30, 525)));
        var request = new RaceCustomizationTypes.Request(3, 1, 5, picks);
        picks.clear();
        assertEquals(1, request.picks().size());
        assertThrows(UnsupportedOperationException.class, () -> request.picks().clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RaceCustomizationTypes.Request(
                                3,
                                1,
                                5,
                                List.of(
                                        new RaceCustomizationTypes.Pick(30, 524),
                                        new RaceCustomizationTypes.Pick(30, 525))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RaceCustomizationTypes.Request(0, 1, 5, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RaceCustomizationTypes.Pick(30, 0));
    }

    @Test
    void descriptiveCatalogCannotHaveAmbiguousOrMissingDefaults() {
        var choices = List.of(new RaceCustomizationTypes.Choice(524, "Choice #524"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RaceCustomizationTypes.Option(30, "Skin Color", choices, 525));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RaceCustomizationTypes.Option(
                                30,
                                "Skin Color",
                                List.of(choices.getFirst(), choices.getFirst()),
                                524));
        var option = new RaceCustomizationTypes.Option(30, "Skin Color", choices, 524);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RaceCustomizationTypes.Catalog(
                                RaceTypes.State.READY,
                                3,
                                1,
                                5,
                                List.of(option, option),
                                List.of(),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RaceCustomizationTypes.Choice(524, "x".repeat(257)));
    }
}
