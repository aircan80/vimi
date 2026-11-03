package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RaceCustomizationTest {
    @Test
    void requirementsSeparatePlayerNpcClassAndUnknownAccountConditions() {
        var standard = new RaceCustomization.Requirement(3, -1, -1, -1, 0, 0, 0, -1, 0, false);
        assertEquals(
                RaceCustomization.Eligibility.ALLOWED, RaceCustomization.evaluate(standard, 1, 0));
        assertEquals(
                RaceCustomization.Eligibility.DENIED,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(2, -1, -1, -1, 0, 0, 0, -1, 0, false),
                        1,
                        0));
        var eyes = new RaceCustomization.Requirement(3, 16351, -1, -1, 0, 0, 0, -1, 0, false);
        assertEquals(RaceCustomization.Eligibility.ALLOWED, RaceCustomization.evaluate(eyes, 1, 0));
        assertEquals(RaceCustomization.Eligibility.DENIED, RaceCustomization.evaluate(eyes, 6, 0));
        assertEquals(
                RaceCustomization.Eligibility.UNKNOWN,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(3, -1, -1, -1, 0, 1, 0, -1, 0, false),
                        1,
                        0));
        assertEquals(
                RaceCustomization.Eligibility.UNKNOWN,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(3, -1, -1, -1, 0, 0, 0, -1, 0, true),
                        1,
                        0));
    }

    @Test
    void raceMasksUseTwoThirtyTwoBitWordsAndRecordedPlayableBit() {
        assertTrue(RaceCustomization.hasRace(0, 3, 32));
        assertTrue(RaceCustomization.hasRace(0, 3, 33));
        assertFalse(RaceCustomization.hasRace(0, 3, 31));
        assertFalse(RaceCustomization.hasRace(0, 3, 63));
        assertTrue(RaceCustomization.hasRace(-1, -1, 63));
        assertFalse(RaceCustomization.hasRace(-1, -1, 94));
    }
}
