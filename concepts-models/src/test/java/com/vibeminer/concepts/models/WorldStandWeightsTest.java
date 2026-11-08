package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2SkeletonFile;
import com.vibeminer.formats.models.M2StandWeights;
import com.vibeminer.rendering.models.MeshAppearance;
import org.junit.jupiter.api.Test;

/**
 * Fading additive and blended texture weights in the World binding (#361), taken at the Stand
 * sample the World poses creatures at (#384): playback time 0 of the resolved Stand.
 */
class WorldStandWeightsTest {
    private static final int FULL = 32767;

    /** An additive glow batch: Combiners_Mod / Diffuse_T1, unlit, blend 4. */
    private static NativeMaterialFixture glow() throws Exception {
        return new NativeMaterialFixture(274, 16, 16, 1, 0x11, 4);
    }

    /** An alpha-blended batch: Combiners_Opaque / Diffuse_T1, blend 2. */
    private static NativeMaterialFixture veil() throws Exception {
        return new NativeMaterialFixture(274, 16, 0, 1, 0, 2);
    }

    /**
     * Declares sequences: a Stand (ID 0) and, with {@code alias}, Stand aliasing sequence 1 (ID 5),
     * which holds the keys. Each played sequence lasts 1000 ms with keys in the model. With {@code
     * keyed}, bone 0 translates during the played sequence and every vertex follows bone 0, so the
     * World has a Stand pose; without, the Stand has no bone keys and the World draws the rest
     * pose.
     */
    private static void stand(NativeMaterialFixture f, boolean alias, boolean keyed) {
        var body = f.body;
        int count = alias ? 2 : 1, sequences = body.position();
        f.declaration(28, count, sequences);
        body.position(sequences + 64 * count);
        body.putShort(sequences, (short) 0);
        body.putInt(sequences + 4, 1000);
        body.putInt(sequences + 12, alias ? 0x40 | 0x20 : 0x20);
        if (alias) {
            body.putShort(sequences + 62, (short) 1);
            body.putShort(sequences + 64, (short) 5);
            body.putInt(sequences + 64 + 4, 1000);
            body.putInt(sequences + 64 + 12, 0x20);
        }
        if (!keyed) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            body.putInt(f.vertices + vertex * 48 + 16, 0);
        }
        int played = alias ? 1 : 0;
        int outerTimes = body.position();
        body.position(outerTimes + 16 * count);
        int keyTimes = body.position();
        body.putInt(0).putInt(1000);
        int keyValues = body.position();
        body.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(4);
        body.putInt(outerTimes + 8 * played, 2).putInt(outerTimes + 8 * played + 4, keyTimes);
        int outerValues = outerTimes + 8 * count;
        body.putInt(outerValues + 8 * played, 2).putInt(outerValues + 8 * played + 4, keyValues);
        int row = f.bones + 16;
        body.putShort(row, (short) 1).putShort(row + 2, (short) -1);
        body.putInt(row + 4, count).putInt(row + 8, outerTimes);
        body.putInt(row + 12, count).putInt(row + 16, outerValues);
    }

    /**
     * Points batch 0's texture weight at track 0, with the given interpolation, on global clock
     * {@code global} (-1: sequence time; 0: a 1000 ms clock this declares). Of its {@code slices}
     * slices, slice {@code keyed} holds {@code values} at 0, 100, 200, … ms; the others hold none.
     */
    private static void weight(
            NativeMaterialFixture f,
            int interpolation,
            int global,
            int slices,
            int keyed,
            int... values) {
        var body = f.body;
        if (global >= 0) {
            int globals = body.position();
            f.declaration(20, 1, globals);
            body.putInt(1000);
        }
        int track = body.position();
        f.declaration(88, 1, track);
        int outerTimes = track + 20, outerValues = outerTimes + 8 * slices;
        int keyTimes = outerValues + 8 * slices, keyValues = keyTimes + 4 * values.length;
        body.putShort((short) interpolation).putShort((short) global);
        body.putInt(slices).putInt(outerTimes).putInt(slices).putInt(outerValues);
        body.position(keyTimes);
        for (int i = 0; i < values.length; i++) {
            body.putInt(i * 100);
        }
        for (int value : values) {
            body.putShort((short) value);
        }
        body.putInt(outerTimes + 8 * keyed, values.length)
                .putInt(outerTimes + 8 * keyed + 4, keyTimes);
        body.putInt(outerValues + 8 * keyed, values.length)
                .putInt(outerValues + 8 * keyed + 4, keyValues);
        body.position((body.position() + 3) & ~3);
        int lookup = body.position();
        f.declaration(144, 1, lookup);
        body.putShort((short) 0).putShort((short) 0);
    }

    private static boolean noted(DisplayModelTypes.Preview world, String fragment) {
        return world.issues().stream().anyMatch(issue -> issue.contains(fragment));
    }

    @Test
    void aWeightThatIsZeroAtTheStandSampleHidesTheSection() throws Exception {
        for (var f : new NativeMaterialFixture[] {glow(), veil()}) {
            stand(f, false, true);
            weight(f, 1, -1, 1, 0, 0, FULL);
            var world = WorldBatchRuleTest.worldBinding(f);
            assertNotNull(world.appearance(), world.message());
            assertTrue(world.appearance().surfaces().isEmpty(), world.issues().toString());
            assertTrue(noted(world, "hidden at the Stand sample"), world.issues().toString());
        }
    }

    @Test
    void aWeightThatIsFullAtTheStandSampleDrawsAsAConstantOne() throws Exception {
        for (var f : new NativeMaterialFixture[] {glow(), veil()}) {
            boolean additive = f.parse().model().materials().getFirst().blendMode() == 4;
            stand(f, false, true);
            // Full at 0, then fading out: the static rule refuses it.
            weight(f, 1, -1, 1, 0, FULL, 0);
            var world = WorldBatchRuleTest.worldBinding(f);
            var surfaces = world.appearance().surfaces();
            assertEquals(1, surfaces.size(), world.issues().toString());
            assertEquals(
                    additive ? MeshAppearance.Blend.ADDITIVE : MeshAppearance.Blend.BLENDED,
                    surfaces.getFirst().material().blend());
            assertEquals(1, world.appearance().textures().size(), "the texture as decoded");
            assertTrue(noted(world, "full at the Stand sample"), world.issues().toString());
        }
    }

    @Test
    void aPartialWeightScalesTheTexturesAlpha() throws Exception {
        for (var f : new NativeMaterialFixture[] {glow(), veil()}) {
            stand(f, false, true);
            // Step keys: 0.25 at the sample (the first key holds before its time too).
            weight(f, 0, -1, 1, 0, FULL / 4, FULL);
            var world = WorldBatchRuleTest.worldBinding(f);
            var surfaces = world.appearance().surfaces();
            assertEquals(1, surfaces.size(), world.issues().toString());
            var textures = world.appearance().textures();
            assertEquals(2, textures.size(), "the decoded texture and its scaled copy");
            int index = surfaces.getFirst().material().stages().getFirst().textureIndex();
            assertEquals(1, index, "the surface samples the scaled copy");
            int alpha = Math.round(FULL / 4 / (float) FULL * 255);
            int[] source = textures.get(0).argb(), scaled = textures.get(index).argb();
            for (int at = 0; at < source.length; at++) {
                assertEquals(source[at] & 0xffffff, scaled[at] & 0xffffff, "colour kept");
                assertEquals(((source[at] >>> 24) * alpha + 127) / 255, scaled[at] >>> 24, "alpha");
            }
            assertTrue(noted(world, "scaling its alpha"), world.issues().toString());
        }
    }

    @Test
    void theAliasTargetsSliceAndGlobalClocksAreSampledLikeThePose() throws Exception {
        // Stand aliases sequence 1: slice 1 is played, so slice 0's zero does not apply.
        var alias = glow();
        stand(alias, true, true);
        weight(alias, 1, -1, 2, 1, FULL, 0);
        var aliased = WorldBatchRuleTest.worldBinding(alias);
        assertEquals(1, aliased.appearance().surfaces().size(), aliased.issues().toString());

        var unplayed = glow();
        stand(unplayed, true, true);
        weight(unplayed, 1, -1, 2, 0, 0, 0);
        var other = WorldBatchRuleTest.worldBinding(unplayed);
        assertEquals(1, other.appearance().surfaces().size(), "no keys in the played slice: full");

        // A global clock is sampled at 0 too: its first key.
        var global = glow();
        stand(global, false, true);
        weight(global, 1, 0, 1, 0, 0, FULL);
        var clocked = WorldBatchRuleTest.worldBinding(global);
        assertTrue(clocked.appearance().surfaces().isEmpty(), clocked.issues().toString());
        assertTrue(noted(clocked, "hidden at the Stand sample"), clocked.issues().toString());
    }

    @Test
    void withoutAStandPoseAFadingWeightKeepsTheConstantRule() throws Exception {
        // No Stand sequence, and a Stand without bone keys (the World draws the rest pose).
        for (boolean declared : new boolean[] {false, true}) {
            var f = glow();
            if (declared) {
                stand(f, false, false);
            }
            weight(f, 1, -1, 1, 0, FULL, 0);
            var both = WorldBatchRuleTest.worldBindings(f, 2);
            var world = both.get(0);
            assertTrue(world.appearance().surfaces().isEmpty(), world.issues().toString());
            assertTrue(
                    noted(world, "not drawn without a Stand pose: The "),
                    world.issues().toString());
            assertTrue(both.get(1).restored(), "a lasting answer is saved");
        }
    }

    @Test
    void anUndecidableWeightKeepsTheSectionOutWithItsReason() throws Exception {
        var curve = veil();
        stand(curve, false, true);
        weight(curve, 2, -1, 1, 0, FULL, 0);
        var world = WorldBatchRuleTest.worldBinding(curve);
        assertTrue(world.appearance().surfaces().isEmpty(), world.issues().toString());
        assertTrue(noted(world, "curve interpolation 2"), world.issues().toString());

        var negative = glow();
        stand(negative, false, true);
        weight(negative, 1, -1, 1, 0, -5, FULL);
        var below = WorldBatchRuleTest.worldBinding(negative);
        assertTrue(below.appearance().surfaces().isEmpty(), below.issues().toString());
        assertTrue(noted(below, "Stand key is negative"), below.issues().toString());

        var clock = glow();
        stand(clock, false, true);
        weight(clock, 1, -1, 1, 0, 0, FULL);
        // Global clock 3 is not declared.
        clock.body.putShort(clock.body.getInt(88 + 4) + 2, (short) 3);
        var undeclared = WorldBatchRuleTest.worldBinding(clock);
        assertTrue(undeclared.appearance().surfaces().isEmpty());
        assertTrue(noted(undeclared, "undeclared global sequence"), undeclared.issues().toString());
    }

    @Test
    void aStandFromAnExternalSkeletonLeavesKeyedWeightsUndecided() throws Exception {
        var fixture = DisplayIdleAnimationsTest.skeletonFixture();
        weight(fixture, 1, -1, 1, 0, FULL, 0);
        var model =
                M2ModelReader.read(
                                AssetSource.bytes(DisplayIdleAnimationsTest.skeletonModel(fixture)))
                        .join();
        var source = AssetSource.bytes(DisplayIdleAnimationsTest.skeleton());
        var skeleton = M2SkeletonFile.read(source, Cancellation.NONE).join();
        var idle = M2IdleAnimation.readSkeleton(source, skeleton, model, Cancellation.NONE).join();
        var weights =
                M2StandWeights.read(
                                AssetSource.bytes(DisplayIdleAnimationsTest.skeletonModel(fixture)),
                                model,
                                idle,
                                Cancellation.NONE)
                        .join();
        assertEquals(1, weights.size());
        assertFalse(weights.getFirst().resolved());
        assertTrue(weights.getFirst().refusal().contains("external skeleton"));
    }

    @Test
    void aLinearTrackWhoseFirstKeyIsAfterZeroHoldsThatKeyAtTheSample() throws Exception {
        var f = glow();
        stand(f, false, true);
        weight(f, 1, -1, 1, 0, FULL / 4, FULL);
        // Keys at 500 and 1000 ms. Like PreviewAnimation, which holds times[0] for time <=
        // times[0], time 0 takes the first key's value, not one interpolated towards it.
        int keyTimes = f.body.getInt(88 + 4) + 20 + 8 + 8;
        f.body.putInt(keyTimes, 500).putInt(keyTimes + 4, 1000);
        var world = WorldBatchRuleTest.worldBinding(f);
        var surfaces = world.appearance().surfaces();
        assertEquals(1, surfaces.size(), world.issues().toString());
        var textures = world.appearance().textures();
        int index = surfaces.getFirst().material().stages().getFirst().textureIndex();
        assertEquals(1, index, "the surface samples the scaled copy");
        int alpha = Math.round(FULL / 4 / (float) FULL * 255);
        int[] source = textures.get(0).argb(), scaled = textures.get(index).argb();
        for (int at = 0; at < source.length; at++) {
            assertEquals(((source[at] >>> 24) * alpha + 127) / 255, scaled[at] >>> 24, "alpha");
        }
    }

    @Test
    void aBindingIsNotSavedWhileTheStandIsUnavailable() throws Exception {
        var f = glow();
        stand(f, false, true);
        weight(f, 1, -1, 1, 0, FULL, 0);
        // Bone 0's Stand key values lie outside the source: the Stand cannot be read (UNAVAILABLE).
        int outerValues = f.body.getInt(f.bones + 16 + 16);
        f.body.putInt(outerValues + 4, 0x7fff0000);
        var both = WorldBatchRuleTest.worldBindings(f, 2);
        var world = both.get(0);
        assertTrue(world.appearance().surfaces().isEmpty(), world.issues().toString());
        assertTrue(
                noted(world, "not drawn without a Stand pose: Idle sequence could not be read"),
                world.issues().toString());
        assertFalse(both.get(1).restored(), "an unavailable Stand is read again, not saved");
    }

    @Test
    void aMalformedTrackIsReadAgainNotSaved() throws Exception {
        var f = glow();
        stand(f, false, true);
        weight(f, 1, -1, 1, 0, FULL, 0);
        // The played slice's keys lie outside the source.
        int outerValues = f.body.getInt(88 + 4) + 20 + 8;
        f.body.putInt(outerValues + 4, 0x7fff0000);
        var both = WorldBatchRuleTest.worldBindings(f, 2);
        var world = both.get(0);
        assertTrue(world.appearance().surfaces().isEmpty(), world.issues().toString());
        // The static opacity facts already fail on it; like the Stand texture weights read, they
        // cannot tell a lasting malformation from a source a later read can clear.
        assertTrue(noted(world, "Static opacity facts are unavailable"), world.issues().toString());
        assertFalse(both.get(1).restored(), "read again on every load, not saved");
    }
}
