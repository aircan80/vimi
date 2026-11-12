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
        // An NPC race (no playable bit) without a class: a mask naming only some classes cannot
        // be checked. A playable race without a class is denied, as before.
        assertEquals(
                RaceCustomization.Eligibility.UNKNOWN, RaceCustomization.evaluate(eyes, 0, -1));
        assertEquals(RaceCustomization.Eligibility.DENIED, RaceCustomization.evaluate(eyes, 0, 0));
        assertEquals(RaceCustomization.Eligibility.DENIED, RaceCustomization.evaluate(eyes, 6, -1));
        assertEquals(
                RaceCustomization.Eligibility.ALLOWED, RaceCustomization.evaluate(standard, 0, 0));
        // The race mask still denies.
        assertEquals(
                RaceCustomization.Eligibility.DENIED,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(3, 16351, 4, 0, 0, 0, 0, -1, 0, false),
                        0,
                        -1));
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
    void anNpcRaceSamplesItsNpcOnlyChoicesAsUncheckedAndAPlayableRaceNever() {
        // ReqType 2 (NPC only, ChrCustomizationReq 12 on 70862): the skin colours of Northrend
        // Skeleton and race 33's models.
        var npcOnly = new RaceCustomization.Requirement(2, 0, -1, -1, 0, 0, 0, -1, 0, false);
        assertEquals(
                RaceCustomization.Eligibility.UNKNOWN, RaceCustomization.evaluate(npcOnly, 0, -1));
        assertEquals(
                RaceCustomization.Eligibility.UNKNOWN, RaceCustomization.evaluate(npcOnly, 1, -1));
        assertEquals(
                RaceCustomization.Eligibility.DENIED, RaceCustomization.evaluate(npcOnly, 1, 0));
        // Its masks still deny: a class outside the mask, or a race mask naming only others.
        assertEquals(
                RaceCustomization.Eligibility.DENIED,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(2, 1, -1, -1, 0, 0, 0, -1, 0, false),
                        6,
                        -1));
        assertEquals(
                RaceCustomization.Eligibility.DENIED,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(2, 0, 4, 0, 0, 0, 0, -1, 0, false),
                        0,
                        -1));
        // A type naming neither players nor NPCs is denied for every race.
        assertEquals(
                RaceCustomization.Eligibility.DENIED,
                RaceCustomization.evaluate(
                        new RaceCustomization.Requirement(0, 0, -1, -1, 0, 0, 0, -1, 0, false),
                        0,
                        -1));
    }

    @Test
    void aMaskNamingEveryRaceAdmitsARaceWithoutAPlayableBit() {
        // NPC races (Gilnean, Haranir, Visage...) have no playable bit; every requirement their
        // models record names every race.
        var every = new RaceCustomization.Requirement(3, 16351, -1, -1, 0, 0, 0, -1, 0, false);
        assertTrue(RaceCustomization.everyRace(-1, -1));
        assertEquals(
                RaceCustomization.Eligibility.ALLOWED, RaceCustomization.evaluate(every, 1, -1));
        // The class mask still applies.
        assertEquals(
                RaceCustomization.Eligibility.DENIED, RaceCustomization.evaluate(every, 6, -1));
        // A mask naming only some races names only playable ones.
        for (long[] masks : new long[][] {{-1, 0}, {0, -1}, {0xfffffffeL, -1}, {4, 0}}) {
            assertFalse(RaceCustomization.everyRace(masks[0], masks[1]));
            assertEquals(
                    RaceCustomization.Eligibility.DENIED,
                    RaceCustomization.evaluate(
                            new RaceCustomization.Requirement(
                                    3, -1, masks[0], masks[1], 0, 0, 0, -1, 0, false),
                            1,
                            -1));
        }
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
