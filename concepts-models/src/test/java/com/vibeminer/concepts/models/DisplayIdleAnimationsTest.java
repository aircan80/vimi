package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2IdleAnimation;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Authored bones: 0 is an ordinary root, 1 a flag-0x8 leaf under 0 that owns two card vertices, 2
 * an unrelated root. Vertices 0 and 1 follow bone 0.
 */
class DisplayIdleAnimationsTest {
    private static final int TRACK_TRANSLATION = 16, TRACK_ROTATION = 36, TRACK_SCALE = 56;

    private static final class Authored {
        final NativeMaterialFixture fixture;

        Authored() throws Exception {
            fixture = new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
            for (int vertex = 0; vertex < 2; vertex++) {
                fixture.body.putInt(fixture.vertices + vertex * 48 + 16, 0);
            }
            fixture.syncBoneProperties();
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

        /**
         * Linear scale keys (1,1,1) at 0 and (3,3,3) at 1000 on global clock 0, which this declares
         * as 1000 ms long.
         */
        Authored scale(int bone) {
            var body = fixture.body;
            int globals = body.position();
            fixture.declaration(20, 1, globals);
            body.putInt(1000);
            int outerTimes = body.position();
            body.putInt(2).putInt(outerTimes + 16);
            int outerValues = body.position();
            body.putInt(2).putInt(outerTimes + 24);
            body.putInt(0).putInt(1000);
            body.putFloat(1).putFloat(1).putFloat(1).putFloat(3).putFloat(3).putFloat(3);
            int row = bone(bone) + TRACK_SCALE;
            body.putShort(row, (short) 1);
            body.putShort(row + 2, (short) 0);
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
                            parsed.rest().surface(),
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
        unsupported("has rotation keys", a -> a.rotate(1));
        unsupported("has child bones", a -> a.parent(2, 1));
    }

    @Test
    void billboardTranslationKeysMoveTheCardPivot() throws Exception {
        // Bone 1 is the 0x8 card leaf; its own translation keys are admitted now.
        var authored = new Authored().translate(0).translate(1);
        var animation = authored.admit();
        var parsed = authored.fixture.parse();
        var rest =
                DisplayModelService.restSurface(
                        parsed.model(), parsed.rest().surface(), Cancellation.NONE);
        var pose = animation.pose(rest.mesh(), 500);
        // Its parent lifts it by 2 at half time and its own keys by 2 more.
        assertEquals(4, pose.pivots().get(1).z() - parsed.model().bones().get(1).pivot().z(), 1e-5);
    }

    @Test
    void billboardScaleKeysOnAGlobalClockSizeTheCard() throws Exception {
        var authored = new Authored().translate(0).scale(1);
        var animation = authored.admit();
        var parsed = authored.fixture.parse();
        var rest =
                DisplayModelService.restSurface(
                        parsed.model(), parsed.rest().surface(), Cancellation.NONE);
        // At 500 ms the global clock is halfway: scale 2.
        var pose = animation.pose(rest.mesh(), 500);
        var restPivot = parsed.model().bones().get(1).pivot();
        var pivot = pose.pivots().get(1);
        float[] before = rest.mesh().positions(), after = pose.mesh().positions();
        for (int vertex = 2; vertex < 4; vertex++) {
            assertEquals(
                    2 * (before[vertex * 3] - restPivot.x()), after[vertex * 3] - pivot.x(), 1e-5);
            assertEquals(
                    2 * (before[vertex * 3 + 1] - restPivot.y()),
                    after[vertex * 3 + 1] - pivot.y(),
                    1e-5);
            assertEquals(
                    2 * (before[vertex * 3 + 2] - restPivot.z()),
                    after[vertex * 3 + 2] - pivot.z(),
                    1e-5);
        }
    }

    private static DisplayIdleAnimations.WorldStand admitWorld(Authored authored) {
        var parsed = authored.fixture.parse();
        var surface =
                DisplayModelService.restSurface(
                        parsed.model(), parsed.rest().surface(), Cancellation.NONE);
        var idle =
                M2IdleAnimation.read(
                                AssetSource.bytes(authored.fixture.modelBytes()),
                                parsed.model(),
                                Cancellation.NONE)
                        .join();
        return DisplayIdleAnimations.admitWorld(
                parsed.model(),
                parsed.rest().surface(),
                idle,
                surface.mesh(),
                surface.normals(),
                Cancellation.NONE);
    }

    @Test
    void theServiceWiresIdleWorldIdleAndStandsToTheirProfiles() throws Exception {
        // Billboard bone 1 with child bones: the preview refuses it, the World admits it.
        var authored = new Authored().translate(0).parent(2, 1);
        var f = authored.fixture;
        var files =
                new com.vibeminer.formats.AssetFilesFixture()
                        .add(100, "1".repeat(32), f.modelBytes())
                        .add(
                                9000,
                                "2".repeat(32),
                                java.util.Arrays.copyOf(f.skin.array(), f.skin.position()));
        try (var tables =
                new com.vibeminer.formats.tables.MemoryTables()
                        .add(
                                files.build(),
                                "enUS",
                                List.of(
                                        NativeMaterialFailureTest.table(
                                                10,
                                                "CreatureDisplayInfo",
                                                "ModelID",
                                                "CreatureModelData",
                                                1),
                                        NativeMaterialFailureTest.table(
                                                11,
                                                "CreatureModelData",
                                                "FileDataID",
                                                "FileData",
                                                100)))
                        .forBuild(files.build(), "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new DisplayModelService(tables, files, null);
            try {
                var preview = service.idle(1, Cancellation.NONE).join();
                var world = service.worldIdle(1, Cancellation.NONE).join();
                var both = service.stands(1, Cancellation.NONE).join();
                assertEquals(
                        DisplayModelTypes.IdleState.UNSUPPORTED,
                        preview.state(),
                        preview.message());
                assertEquals(DisplayModelTypes.IdleState.AVAILABLE, world.state(), world.message());
                assertArrayEquals(new int[] {2, 3}, world.leftOut());
                assertEquals(DisplayModelTypes.IdleState.UNSUPPORTED, both.get(0).state());
                assertEquals(DisplayModelTypes.IdleState.AVAILABLE, both.get(1).state());
                assertArrayEquals(new int[] {2, 3}, both.get(1).leftOut());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void theServiceAnswersEachProfileWithItsOwnAdmission() throws Exception {
        // Billboard bone 1 with child bones: the preview refuses, the World admits.
        var authored = new Authored().translate(0).parent(2, 1);
        var parsed = authored.fixture.parse();
        var geometry = parsed.rest().surface();
        var surface = DisplayModelService.restSurface(parsed.model(), geometry, Cancellation.NONE);
        var idle =
                M2IdleAnimation.read(
                                AssetSource.bytes(authored.fixture.modelBytes()),
                                parsed.model(),
                                Cancellation.NONE)
                        .join();
        var preview =
                DisplayModelService.admitted(
                        null, parsed.model(), geometry, surface, idle, false, Cancellation.NONE);
        var world =
                DisplayModelService.admitted(
                        null, parsed.model(), geometry, surface, idle, true, Cancellation.NONE);
        assertEquals(DisplayModelTypes.IdleState.UNSUPPORTED, preview.state());
        assertTrue(preview.message().contains("has child bones"), preview.message());
        assertEquals(DisplayModelTypes.IdleState.AVAILABLE, world.state());
        assertArrayEquals(new int[] {2, 3}, world.leftOut());
        assertTrue(
                world.message().endsWith("2 vertices weighted to billboard bones left out."),
                world.message());
    }

    @Test
    void theWorldProfilePosesBillboardBonesAndListsTheirVerticesAsLeftOut() throws Exception {
        // Rotation keys and child bones on billboard bone 1 are refused for the preview but not
        // for the World, which leaves out the card vertices 2 and 3 weighted to it.
        for (Consumer<Authored> change :
                List.<Consumer<Authored>>of(a -> a.rotate(1), a -> a.parent(2, 1), a -> {})) {
            var authored = new Authored().translate(0);
            change.accept(authored);
            var world = admitWorld(authored);
            assertArrayEquals(new int[] {2, 3}, world.leftOut());
            assertEquals(3, world.animation().boneCount());
        }
        // A descendant of a billboard bone is left out too: vertex 3 moves to bone 2, a child of
        // the billboard bone 1.
        var descendant = new Authored().translate(0).parent(2, 1);
        descendant.fixture.body.putInt(descendant.fixture.vertices + 3 * 48 + 16, 2);
        descendant.fixture.syncBoneProperties();
        assertArrayEquals(new int[] {2, 3}, admitWorld(descendant).leftOut());
        // A cylindrical billboard (0x40) is left out the same way.
        assertArrayEquals(
                new int[] {2, 3},
                admitWorld(new Authored().translate(0).flags(1, 0x240)).leftOut());
        // Without a billboard bone nothing is left out.
        assertArrayEquals(
                new int[0], admitWorld(new Authored().translate(0).flags(1, 0x200)).leftOut());
        // Parent-inheritance flags on a bone that is not a billboard are still refused.
        var inherited = new Authored().translate(0).flags(0, 0x204);
        var failure =
                assertThrows(DisplayIdleAnimations.Unsupported.class, () -> admitWorld(inherited));
        assertTrue(failure.getMessage().contains("0x4"), failure.getMessage());
    }

    @Test
    void cylindricalCardLeavesAreAdmittedLikeSphericalOnes() throws Exception {
        for (int flags : new int[] {0x40, 0x20}) {
            var authored = new Authored().translate(0).translate(1).flags(1, flags);
            var animation = authored.admit();
            var parsed = authored.fixture.parse();
            var rest =
                    DisplayModelService.restSurface(
                            parsed.model(), parsed.rest().surface(), Cancellation.NONE);
            var pose = animation.pose(rest.mesh(), 500);
            assertEquals(
                    4, pose.pivots().get(1).z() - parsed.model().bones().get(1).pivot().z(), 1e-5);
        }
        unsupported("0x10", a -> a.flags(1, 0x10));
        unsupported("has rotation keys", a -> a.flags(1, 0x40).rotate(1));
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
                        rest.surface(),
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

    @Test
    void aSkinPaletteThatDiffersFromTheVertexIndicesIsRefused() throws Exception {
        // The bone lookup swaps bones 1 and 2: the section's palette slot 1 names bone 2, while
        // the vertices name bone 1 directly.
        unsupported(
                "Skin section 0 names bones through a palette that differs",
                authored -> {
                    int lookup = authored.fixture.body.getInt(124);
                    authored.fixture.body.putShort(lookup + 2, (short) 2);
                    authored.fixture.body.putShort(lookup + 4, (short) 1);
                });
    }

    /**
     * A skeleton of {@code bones} bones: bone 0 a root translating (0,0,0)-(0,0,4), every other
     * bone a keyless child of bone 0.
     */
    static byte[] skeleton(int bones) {
        var s = java.nio.ByteBuffer.allocate(32 + 64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        s.putInt(8, 1).putInt(12, 32);
        s.putInt(32 + 4, 1000).putInt(32 + 12, 0x20);
        var b =
                java.nio.ByteBuffer.allocate(16 + bones * 88 + 48)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, bones).putInt(4, 16);
        for (int bone = 0; bone < bones; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, -1).putInt(row + 4, 512).putShort(row + 8, (short) (bone == 0 ? -1 : 0));
            for (int track : new int[] {16, 36, 56}) {
                b.putShort(row + track + 2, (short) -1);
            }
        }
        int data = 16 + bones * 88, row = 16 + TRACK_TRANSLATION;
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
    void rigsOfMoreThan256BonesBindThroughAnIdentityPalette() throws Exception {
        var fixture = skeletonFixture();
        var model =
                com.vibeminer.formats.models.M2ModelReader.read(
                                AssetSource.bytes(skeletonModel(fixture)))
                        .join();
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
        var source = AssetSource.bytes(skeleton(300));
        var skeleton =
                com.vibeminer.formats.models.M2SkeletonFile.read(source, Cancellation.NONE).join();
        var idle = M2IdleAnimation.readSkeleton(source, skeleton, model, Cancellation.NONE).join();
        assertEquals(300, idle.rig().size());
        var animation =
                DisplayIdleAnimations.admit(
                        model,
                        rest.surface(),
                        idle,
                        surface.mesh(),
                        surface.normals(),
                        Cancellation.NONE);
        assertEquals(300, animation.boneCount());
        float[] before = surface.mesh().positions();
        float[] after = animation.pose(surface.mesh(), 500).mesh().positions();
        for (int vertex = 0; vertex < surface.mesh().vertexCount(); vertex++) {
            assertEquals(before[vertex * 3 + 2] + 2, after[vertex * 3 + 2], 1e-5);
        }
        var world =
                DisplayIdleAnimations.admitWorld(
                        model,
                        rest.surface(),
                        idle,
                        surface.mesh(),
                        surface.normals(),
                        Cancellation.NONE);
        assertEquals(300, world.animation().boneCount());
        assertEquals(0, world.leftOut().length);
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
