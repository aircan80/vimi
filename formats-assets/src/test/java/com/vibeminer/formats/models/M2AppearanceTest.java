package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class M2AppearanceTest {
    private record Parsed(
            byte[] modelBytes, byte[] skinBytes, M2Types.Model model, M2Types.GeometrySkin skin) {}

    private static Parsed parsed(String name) {
        byte[] mb = M2ReadersTest.golden("appearance-" + name + ".m2"),
                sb = M2ReadersTest.golden("appearance-" + name + ".skin");
        var model = M2ModelReader.read(AssetSource.bytes(mb)).join();
        var skin =
                M2GeometrySkinReader.read(AssetSource.bytes(sb), model, null, Cancellation.NONE)
                        .join();
        return new Parsed(mb, sb, model, skin);
    }

    private static M2Appearance.Plan plan(Parsed p) {
        return M2Appearance.read(
                        AssetSource.bytes(p.modelBytes),
                        p.model,
                        AssetSource.bytes(p.skinBytes),
                        p.skin,
                        Cancellation.NONE)
                .join();
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "no-keys"})
    void csharpGoldenConstantAndNoKeyWeightsAdmitCreatureVariation(String name) {
        Parsed p = parsed(name);
        var facts =
                M2Appearance.readModel(AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                        .join();
        assertEquals(List.of(true), facts.staticWeights());
        var plan = plan(p);
        assertNull(plan.decline());
        assertEquals(1, plan.draws().size());
        var texture = plan.textures().getFirst();
        assertEquals(M2Appearance.TextureBinding.CREATURE_VARIATION, texture.binding());
        assertEquals(0, texture.variation());
        assertEquals(0, texture.fileDataId());
        assertTrue(texture.wrapU());
        assertTrue(texture.wrapV());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-weight", "external-weight"})
    void partialOpacityAndExternalAnimationRemainUnproven(String name) {
        Parsed p = parsed(name);
        var facts =
                M2Appearance.readModel(AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                        .join();
        assertEquals(List.of(false), facts.staticWeights());
        // Only the external track is left unproven for want of its keys: a partial one is proven.
        assertEquals(List.of(name.equals("external-weight")), facts.externalWeights());
        var plan = plan(p);
        assertEquals(M2Appearance.Refusal.TEXTURE_WEIGHT, plan.decline().reason());
        assertTrue(plan.draws().isEmpty());
        assertTrue(plan.textures().isEmpty());
    }

    /**
     * The simple model's one weight track rewritten with one slice per entry of {@code keys}, each
     * of one key. Only the first {@code flags.length} slices have a sequence, whose flags they are.
     */
    private static M2Appearance.ModelFacts weights(int[] flags, int[] keys) {
        return weights(flags, keys, false, null);
    }

    /** A skeleton file's facts: its parent and its sequences' flags. */
    private static M2SkeletonFile.Skeleton skeleton(long parent, int... flags) {
        var sequences = new java.util.ArrayList<M2SkeletonFile.Sequence>();
        for (int i = 0; i < flags.length; i++) {
            sequences.add(new M2SkeletonFile.Sequence(0, i, 1000, flags[i], 0));
        }
        return new M2SkeletonFile.Skeleton(
                0, 0, List.of(), parent, List.of(), sequences, List.of(), List.of(), List.of());
    }

    /**
     * With {@code skid}, the model also names a skeleton file (SKID), read as {@code skeleton}
     * (null: not readable).
     */
    private static M2Appearance.ModelFacts weights(
            int[] flags, int[] keys, boolean skid, M2SkeletonFile.Skeleton skeleton) {
        byte[] simple = parsed("simple").modelBytes;
        var in = java.nio.ByteBuffer.wrap(simple).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int md = 8, size = in.getInt(4), end = md + size;
        int at = (size + 3) & ~3;
        int seq = at, slices = seq + flags.length * 64, keyAt = slices + keys.length * 8;
        int grown = keyAt + keys.length * 2;
        grown = (grown + 3) & ~3;
        byte[] model = new byte[simple.length + (grown - size)];
        System.arraycopy(simple, 0, model, 0, end);
        System.arraycopy(simple, end, model, md + grown, simple.length - end);
        var b = java.nio.ByteBuffer.wrap(model).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(4, grown);
        int first = in.getInt(md + 0x20);
        for (int i = 0; i < flags.length; i++) {
            System.arraycopy(simple, md + first, model, md + seq + i * 64, 64);
            b.putInt(md + seq + i * 64 + 12, flags[i]);
        }
        b.putInt(md + 0x1c, flags.length).putInt(md + 0x20, seq);
        int track = in.getInt(md + 0x5c);
        b.putInt(md + track + 12, keys.length).putInt(md + track + 16, slices);
        for (int i = 0; i < keys.length; i++) {
            b.putInt(md + slices + i * 8, 1).putInt(md + slices + i * 8 + 4, keyAt + i * 2);
            b.putShort(md + keyAt + i * 2, (short) keys[i]);
        }
        if (skid) {
            int length = model.length;
            model = java.util.Arrays.copyOf(model, length + 12);
            var chunk = java.nio.ByteBuffer.wrap(model).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            chunk.put(length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            chunk.putInt(length + 4, 4).putInt(length + 8, 123456);
        }
        var parsedModel = M2ModelReader.read(AssetSource.bytes(model)).join();
        return M2Appearance.readModel(
                        AssetSource.bytes(model), parsedModel, skeleton, Cancellation.NONE)
                .join();
    }

    @Test
    void anExternalSliceIsAssumableOnlyWhenNoKeyInTheModelProvesTheTrackPartial() {
        // One slice external, one stored and full: unproven, and external.
        var external = weights(new int[] {0, 0x20}, new int[] {32767, 32767});
        assertEquals(List.of(false), external.staticWeights());
        assertEquals(List.of(true), external.externalWeights());
        // One slice external, one stored and partial, in either order: proven partial.
        for (int[] flags : List.of(new int[] {0, 0x20}, new int[] {0x20, 0})) {
            int[] keys = flags[0] == 0 ? new int[] {32767, 0} : new int[] {0, 32767};
            var mixed = weights(flags, keys);
            assertEquals(List.of(false), mixed.staticWeights());
            assertEquals(List.of(false), mixed.externalWeights());
        }
        // Both stored and full: proven, nothing assumed.
        var stored = weights(new int[] {0x20, 0x20}, new int[] {32767, 32767});
        assertEquals(List.of(true), stored.staticWeights());
        assertEquals(List.of(false), stored.externalWeights());
        // A slice past the last sequence: nothing says where its keys are; unproven, not external.
        var orphan = weights(new int[] {0x20}, new int[] {32767, 32767});
        assertEquals(List.of(false), orphan.staticWeights());
        assertEquals(List.of(false), orphan.externalWeights());
        var none = weights(new int[] {}, new int[] {32767});
        assertEquals(List.of(false), none.staticWeights());
        assertEquals(List.of(false), none.externalWeights());
        // Even beside a skeleton file, a model that keeps some sequences of its own says nothing
        // about a slice past them.
        var past = weights(new int[] {0x20}, new int[] {32767, 32767}, true, skeleton(0, 0, 0));
        assertEquals(List.of(false), past.staticWeights());
        assertEquals(List.of(false), past.externalWeights());
    }

    @Test
    void aModelWhoseSequencesAreInItsSkeletonReadsWhereTheSkeletonSaysItsKeysAre() {
        int[] none = {};
        // Flagged 0x20 in the skeleton: the key is in the model, read and proven.
        var partial = weights(none, new int[] {0}, true, skeleton(0, 0x20));
        assertEquals(List.of(false), partial.staticWeights());
        assertEquals(List.of(false), partial.externalWeights());
        var full = weights(none, new int[] {32767}, true, skeleton(0, 0x20));
        assertEquals(List.of(true), full.staticWeights());
        assertEquals(List.of(false), full.externalWeights());
        // Not flagged: the keys are in an animation file.
        var external = weights(none, new int[] {32767}, true, skeleton(0, 0));
        assertEquals(List.of(false), external.staticWeights());
        assertEquals(List.of(true), external.externalWeights());
        // A skeleton with a parent still has its own sequences, and they are the model's.
        var child = weights(none, new int[] {32767}, true, skeleton(77, 0x20));
        assertEquals(List.of(true), child.staticWeights());
        var childPartial = weights(none, new int[] {0}, true, skeleton(77, 0x20));
        assertEquals(List.of(false), childPartial.staticWeights());
        assertEquals(List.of(false), childPartial.externalWeights());
        // But a child skeleton's unflagged sequence is unproven, not external.
        var childExternal = weights(none, new int[] {32767}, true, skeleton(77, 0));
        assertEquals(List.of(false), childExternal.staticWeights());
        assertEquals(List.of(false), childExternal.externalWeights());
        // Past the skeleton's sequences, an unreadable skeleton, one with no sequences of its own
        // (a parent's are not read), or a skeleton the model does not name: unproven.
        for (var unproven :
                List.of(
                        weights(none, new int[] {32767, 32767}, true, skeleton(0, 0x20)),
                        weights(none, new int[] {32767}, true, null),
                        weights(none, new int[] {32767}, true, skeleton(77)),
                        weights(none, new int[] {32767}, false, skeleton(0, 0x20)))) {
            assertEquals(List.of(false), unproven.staticWeights());
            assertEquals(List.of(false), unproven.externalWeights());
        }
    }

    @Test
    void csharpGoldenTwoStageEnvCombinerAllowsOneWeightEntry() {
        Parsed p = parsed("two-stage");
        var plan = plan(p);
        assertNull(plan.decline());
        var draw = plan.draws().getFirst();
        assertEquals(M2Combiners.Combiner.OPAQUE_MOD2X_NA_ALPHA, draw.combiner());
        assertEquals(M2Combiners.Coordinate.UV0, draw.coordinate());
        assertEquals(M2Combiners.Coordinate.ENV, draw.secondCoordinate());
        assertEquals(1, draw.secondTextureOrdinal());
        assertEquals(1234, plan.textures().getFirst().fileDataId());
        assertEquals(2, plan.textures().size());
    }

    @Test
    void csharpGoldenAlphaKeyUsesTextureAlphaAndRenderBits() {
        var plan = plan(parsed("alpha-key"));
        assertNull(plan.decline());
        var draw = plan.draws().getFirst();
        assertEquals(M2Combiners.Combiner.MOD, draw.combiner());
        assertTrue(draw.alphaKey());
        assertTrue(draw.twoSided());
        assertTrue(draw.depthTest());
        assertTrue(draw.depthWrite());
        assertEquals(128, M2Appearance.ALPHA_REFERENCE);
    }

    @Test
    void blendedOnlySurfaceAndUnknownRenderBitsRemainDistinctDeclines() {
        var blend = plan(parsed("blended"));
        assertEquals(M2Appearance.Refusal.BLEND_UNSUPPORTED, blend.decline().reason());
        assertTrue(blend.draws().isEmpty());
        var render = plan(parsed("bad-render"));
        assertEquals(M2Appearance.Refusal.MATERIAL_UNSUPPORTED, render.decline().reason());
        assertTrue(render.decline().message().contains("0x02"));
    }

    @Test
    void sharedWeightSliceArraysReuseProofWhileDistinctArraysExhaustGlobalBound() {
        for (String name : List.of("shared-empty-slices", "unique-empty-slices")) {
            Parsed p = parsed(name);
            var facts =
                    M2Appearance.readModel(
                                    AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                            .join();
            assertEquals(32, facts.staticWeights().size());
            for (int i = 0; i < 32; i++) {
                assertEquals(name.startsWith("shared") || i < 16, facts.staticWeights().get(i));
            }
            assertNull(plan(p).decline());
        }
    }

    @Test
    void textureLookupMustNameADeclaredTexture() {
        Parsed base = parsed("two-stage");
        byte[] model = base.modelBytes.clone();
        int combo = M2ReadersTest.get(model, 8 + 132) + 8;
        M2ReadersTest.word(model, combo, 99);
        var parsedModel = M2ModelReader.read(AssetSource.bytes(model)).join();
        var skin =
                M2GeometrySkinReader.read(
                                AssetSource.bytes(base.skinBytes),
                                parsedModel,
                                null,
                                Cancellation.NONE)
                        .join();
        var result =
                M2Appearance.read(
                                AssetSource.bytes(model),
                                parsedModel,
                                AssetSource.bytes(base.skinBytes),
                                skin,
                                Cancellation.NONE)
                        .join();
        assertEquals(M2Appearance.Refusal.MATERIAL_UNSUPPORTED, result.decline().reason());
        assertFalse(result.decline().message().isBlank());
    }
}
