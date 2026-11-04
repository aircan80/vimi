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
}
