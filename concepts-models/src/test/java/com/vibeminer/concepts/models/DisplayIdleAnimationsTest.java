package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Authored bones: 0 is an ordinary root, 1 a flag-0x8 leaf under 0 that owns two card vertices, 2
 * an unrelated root. Vertices 0 and 1 follow bone 0.
 */
class DisplayIdleAnimationsTest {
    private static final int TRACK_TRANSLATION = 16, TRACK_ROTATION = 36;

    private static final class Authored {
        final NativeMaterialFixture fixture;

        Authored() throws Exception {
            fixture = new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
            for (int vertex = 0; vertex < 2; vertex++) {
                fixture.body.putInt(fixture.vertices + vertex * 48 + 16, 0);
            }
            int sequences = fixture.body.position();
            fixture.declaration(28, 1, sequences);
            fixture.body.position(sequences + 64);
            fixture.body.putShort(sequences, (short) 0);
            fixture.body.putInt(sequences + 4, 1000);
            fixture.body.putInt(sequences + 12, 0x20);
        }

        int bone(int index) {
            return fixture.bones + index * 88;
        }

        Authored flags(int bone, int flags) {
            fixture.body.putInt(bone(bone) + 4, flags);
            return this;
        }

        Authored parent(int bone, int parent) {
            fixture.body.putShort(bone(bone) + 8, (short) parent);
            return this;
        }

        /** One Stand slice with linear translation keys (0,0,0) at 0 and (0,0,4) at 1000. */
        Authored translate(int bone) {
            var body = fixture.body;
            int outerTimes = body.position();
            body.putInt(2).putInt(outerTimes + 16);
            int outerValues = body.position();
            body.putInt(2).putInt(outerTimes + 24);
            body.putInt(0).putInt(1000);
            body.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(4);
            int row = bone(bone) + TRACK_TRANSLATION;
            body.putShort(row, (short) 1);
            body.putShort(row + 2, (short) -1);
            body.putInt(row + 4, 1).putInt(row + 8, outerTimes);
            body.putInt(row + 12, 1).putInt(row + 16, outerValues);
            return this;
        }

        /** A constant identity rotation key, enough to make the bone keyed. */
        Authored rotate(int bone) {
            var body = fixture.body;
            int outerTimes = body.position();
            body.putInt(1).putInt(outerTimes + 16);
            int outerValues = body.position();
            body.putInt(1).putInt(outerValues + 12);
            body.putInt(0);
            body.putShort((short) 32767).putShort((short) 32767).putShort((short) 32767);
            body.putShort((short) -1);
            int row = bone(bone) + TRACK_ROTATION;
            body.putShort(row, (short) 0);
            body.putShort(row + 2, (short) -1);
            body.putInt(row + 4, 1).putInt(row + 8, outerTimes);
            body.putInt(row + 12, 1).putInt(row + 16, outerValues);
            return this;
        }

        PreviewAnimation admit() {
            var parsed = fixture.parse();
            var surface =
                    DisplayModelService.restSurface(
                            parsed.model(), parsed.rest().surface(), Cancellation.NONE);
            var idle =
                    M2IdleAnimation.read(
                                    AssetSource.bytes(fixture.modelBytes()),
                                    parsed.model(),
                                    Cancellation.NONE)
                            .join();
            var animation =
                    DisplayIdleAnimations.admit(
                            parsed.model(),
                            parsed.rest().surface().vertexLookup(),
                            idle,
                            surface.mesh(),
                            surface.normals(),
                            Cancellation.NONE);
            assertTrue(animation.matches(surface.mesh()));
            return animation;
        }
    }

    private static void unsupported(String fragment, Consumer<Authored> change) throws Exception {
        var authored = new Authored().translate(0);
        change.accept(authored);
        var failure = assertThrows(DisplayIdleAnimations.Unsupported.class, authored::admit);
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    @Test
    void ordinaryBonesAnimateAndRigidCardsFollowTheirParentPivot() throws Exception {
        var authored = new Authored().translate(0);
        var animation = authored.admit();
        var parsed = authored.fixture.parse();
        var rest =
                DisplayModelService.restSurface(
                        parsed.model(), parsed.rest().surface(), Cancellation.NONE);
        var pose = animation.pose(rest.mesh(), 500);
        float[] before = rest.mesh().positions(), after = pose.mesh().positions();
        for (int vertex = 0; vertex < rest.mesh().vertexCount(); vertex++) {
            assertEquals(before[vertex * 3], after[vertex * 3], 1e-6);
            assertEquals(before[vertex * 3 + 1], after[vertex * 3 + 1], 1e-6);
            // Bone 0 lifts by 2 at half time; card members under bone 1 follow its pivot.
            assertEquals(before[vertex * 3 + 2] + 2, after[vertex * 3 + 2], 1e-5);
        }
        assertEquals(2, pose.pivots().get(1).z() - parsed.model().bones().get(1).pivot().z(), 1e-5);
        assertEquals(3, animation.boneCount());
        assertEquals(1000, animation.durationMillis());
    }

    @Test
    void globalClocksLongerThanTheStandBoundAreAdmittedThroughTheReader() throws Exception {
        var authored = new Authored();
        var body = authored.fixture.body;
        int globals = body.position();
        authored.fixture.declaration(20, 1, globals);
        body.putInt(1_079_000);
        authored.translate(0);
        // The translation track runs on global clock 0 instead of the Stand.
        body.putShort(authored.bone(0) + TRACK_TRANSLATION + 2, (short) 0);
        var animation = authored.admit();
        var parsed = authored.fixture.parse();
        var rest =
                DisplayModelService.restSurface(
                        parsed.model(), parsed.rest().surface(), Cancellation.NONE);
        float[] before = rest.mesh().positions();
        // Keys (0,0,0)@0 and (0,0,4)@1000 hold after the last key; the clock wraps at 1,079,000.
        float[] held = animation.pose(rest.mesh(), 500_000).mesh().positions();
        float[] wrapped = animation.pose(rest.mesh(), 1_079_500).mesh().positions();
        assertEquals(before[2] + 4, held[2], 1e-5);
        assertEquals(before[2] + 2, wrapped[2], 1e-5);
    }

    @Test
    void flagsOfBonesThatMoveNoVertexAreIrrelevant() throws Exception {
        new Authored().translate(0).flags(2, 0x204).admit();
    }

    @Test
    void unreviewedFlagsOnRenderedChainsAreUnsupported() throws Exception {
        unsupported("0x4", a -> a.flags(0, 0x204));
        unsupported("0x1", a -> a.flags(0, 0x201));
        unsupported("0xc", a -> a.flags(1, 0xc));
        unsupported("0x10", a -> a.flags(1, 0x10));
        unsupported("keyless leaf", a -> a.rotate(1));
        unsupported("keyless leaf", a -> a.parent(2, 1));
    }

    @Test
    void sequencesWithoutBoneKeysKeepTheStaticPreview() throws Exception {
        var authored = new Authored();
        var failure = assertThrows(DisplayIdleAnimations.Unsupported.class, authored::admit);
        assertTrue(failure.getMessage().contains("no bone keys"));
    }

    private static byte[] chunk(String id, byte[] payload) {
        return java.nio.ByteBuffer.allocate(8 + payload.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /**
     * A skeleton: one Stand (1000 ms); bone 0 a root translating (0,0,0)-(0,0,4), bone 1 its child.
     */
    static byte[] skeleton() {
        var s = java.nio.ByteBuffer.allocate(32 + 64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        s.putInt(8, 1).putInt(12, 32);
        s.putInt(32 + 4, 1000).putInt(32 + 12, 0x20);
        var b =
                java.nio.ByteBuffer.allocate(16 + 2 * 88 + 48)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 2).putInt(4, 16);
        for (int bone = 0; bone < 2; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, -1).putInt(row + 4, 512).putShort(row + 8, (short) (bone - 1));
            for (int track : new int[] {16, 36, 56}) {
                b.putShort(row + track + 2, (short) -1);
            }
        }
        int data = 16 + 2 * 88, row = 16 + TRACK_TRANSLATION;
        b.putShort(row, (short) 1).putInt(row + 4, 1).putInt(row + 8, data);
        b.putInt(row + 12, 1).putInt(row + 16, data + 8);
        b.putInt(data, 2).putInt(data + 4, data + 16);
        b.putInt(data + 8, 2).putInt(data + 12, data + 24);
        b.putInt(data + 16, 0).putInt(data + 20, 1000);
        b.putFloat(data + 24 + 20, 4);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(chunk("SKL1", new byte[16]));
        out.writeBytes(chunk("SKS1", s.array()));
        out.writeBytes(chunk("SKB1", b.array()));
        return out.toByteArray();
    }

    @Test
    void skeletonModelsAnimateWithTheSkeletonsBones() throws Exception {
        // The model stores no bones; its vertices name bone 1 of its external skeleton.
        var fixture = skeletonFixture();
        var model =
                com.vibeminer.formats.models.M2ModelReader.read(
                                AssetSource.bytes(skeletonModel(fixture)))
                        .join();
        assertEquals(0, model.bones().size());
        var rest =
                com.vibeminer.formats.models.M2RestDrawSkinReader.read(
                                AssetSource.bytes(
                                        java.util.Arrays.copyOf(
                                                fixture.skin.array(), fixture.skin.position())),
                                model,
                                null,
                                Cancellation.NONE)
                        .join();
        var surface = DisplayModelService.restSurface(model, rest.surface(), Cancellation.NONE);
        var source = AssetSource.bytes(skeleton());
        var skeleton =
                com.vibeminer.formats.models.M2SkeletonFile.read(source, Cancellation.NONE).join();
        var idle = M2IdleAnimation.readSkeleton(source, skeleton, model, Cancellation.NONE).join();
        var animation =
                DisplayIdleAnimations.admit(
                        model,
                        rest.surface().vertexLookup(),
                        idle,
                        surface.mesh(),
                        surface.normals(),
                        Cancellation.NONE);
        assertEquals(2, animation.boneCount());
        float[] before = surface.mesh().positions();
        float[] after = animation.pose(surface.mesh(), 500).mesh().positions();
        for (int vertex = 0; vertex < surface.mesh().vertexCount(); vertex++) {
            // Bone 1 follows its parent, bone 0, which lifts by 2 at half time.
            assertEquals(before[vertex * 3 + 2] + 2, after[vertex * 3 + 2], 1e-5);
        }
    }

    /** The material fixture with no bones of its own; its vertices name bone 1. */
    static NativeMaterialFixture skeletonFixture() throws Exception {
        var fixture = new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
        fixture.declaration(44, 0, fixture.bones);
        return fixture;
    }

    /** The fixture's model bytes naming external skeleton 4242. */
    static byte[] skeletonModel(NativeMaterialFixture fixture) {
        byte[] plain = fixture.modelBytes();
        byte[] bytes = java.util.Arrays.copyOf(plain, plain.length + 12);
        java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(plain.length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(plain.length + 4, 4)
                .putInt(plain.length + 8, 4242);
        return bytes;
    }
}
