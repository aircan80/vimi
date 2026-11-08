package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class M2IdleAnimationTest {
    private static final int GLOBALS = 0, SEQUENCES = 16, BONES = SEQUENCES + 128;
    private static final int DATA = BONES + 176, EXTRA = DATA + 512;

    /** Authored body annex: one global clock, Walk then Stand, two bones with keyed tracks. */
    static final class Annex {
        final ByteBuffer buffer;
        final int base, at;
        int cursor;

        Annex(ByteBuffer buffer, int base, int at) {
            this.buffer = buffer;
            this.base = base;
            this.at = at;
            cursor = at + DATA;
        }

        int body(int absolute) {
            return absolute - base;
        }

        int sequence(int index) {
            return at + SEQUENCES + index * 64;
        }

        int track(int bone, int offset) {
            return at + BONES + bone * 88 + offset;
        }

        int allocate(int bytes) {
            int start = cursor;
            cursor += bytes;
            return start;
        }

        /** Writes a track whose slice {@code slice} holds the keys; other slices are empty. */
        void keys(
                int bone,
                int offset,
                int interpolation,
                int global,
                int slices,
                int slice,
                int[] times,
                Consumer<ByteBuffer> values,
                int stride) {
            int row = track(bone, offset);
            int outerTimes = allocate(slices * 8), outerValues = allocate(slices * 8);
            int keyTimes = allocate(times.length * 4), keyValues = allocate(times.length * stride);
            buffer.putShort(row, (short) interpolation);
            buffer.putShort(row + 2, (short) global);
            buffer.putInt(row + 4, slices);
            buffer.putInt(row + 8, body(outerTimes));
            buffer.putInt(row + 12, slices);
            buffer.putInt(row + 16, body(outerValues));
            buffer.putInt(outerTimes + slice * 8, times.length);
            buffer.putInt(outerTimes + slice * 8 + 4, body(keyTimes));
            buffer.putInt(outerValues + slice * 8, times.length);
            buffer.putInt(outerValues + slice * 8 + 4, body(keyValues));
            for (int i = 0; i < times.length; i++) {
                buffer.putInt(keyTimes + i * 4, times[i]);
            }
            values.accept(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(keyValues));
        }
    }

    static byte[] authored(int version, Consumer<Annex> customize) {
        byte[] original = M2ReadersTest.golden("model-" + version + ".m2");
        var parsed = M2ModelReader.read(AssetSource.bytes(original)).join();
        int base = Math.toIntExact(parsed.baseOffset());
        int bodyBytes = Math.toIntExact(parsed.baseBytes());
        int insertion = base + bodyBytes;
        byte[] bytes = new byte[original.length + EXTRA];
        System.arraycopy(original, 0, bytes, 0, insertion);
        System.arraycopy(
                original, insertion, bytes, insertion + EXTRA, original.length - insertion);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(base - 4, bodyBytes + EXTRA);
        var annex = new Annex(buffer, base, insertion);
        buffer.putInt(base + 20, 1);
        buffer.putInt(base + 24, bodyBytes + GLOBALS);
        buffer.putInt(insertion + GLOBALS, 500);
        buffer.putInt(base + 28, 2);
        buffer.putInt(base + 32, bodyBytes + SEQUENCES);
        buffer.putShort(annex.sequence(0), (short) 4);
        buffer.putInt(annex.sequence(0) + 4, 800);
        buffer.putInt(annex.sequence(0) + 12, 0x20);
        buffer.putShort(annex.sequence(1), (short) 0);
        buffer.putInt(annex.sequence(1) + 4, 1000);
        buffer.putInt(annex.sequence(1) + 12, 0x20);
        buffer.putInt(base + 44, 2);
        buffer.putInt(base + 48, bodyBytes + BONES);
        for (int bone = 0; bone < 2; bone++) {
            int row = annex.track(bone, 0);
            buffer.putInt(row, -1);
            buffer.putInt(row + 4, bone == 0 ? 0x200 : 0);
            buffer.putShort(row + 8, (short) (bone - 1));
            buffer.putFloat(row + 76, bone);
            for (int track : new int[] {16, 36, 56}) {
                buffer.putShort(row + track + 2, (short) -1);
            }
        }
        annex.keys(
                0,
                16,
                1,
                -1,
                2,
                1,
                new int[] {0, 1000},
                v -> v.putFloat(0).putFloat(0).putFloat(0).putFloat(10).putFloat(0).putFloat(0),
                12);
        // Compressed identity: 32767 encodes 0 and -1 encodes 1.
        annex.keys(
                0,
                36,
                0,
                -1,
                2,
                1,
                new int[] {0},
                v ->
                        v.putShort((short) 32767)
                                .putShort((short) 32767)
                                .putShort((short) 32767)
                                .putShort((short) -1),
                8);
        annex.keys(
                1,
                56,
                1,
                0,
                1,
                0,
                new int[] {0, 500},
                v -> v.putFloat(1).putFloat(1).putFloat(1).putFloat(2).putFloat(2).putFloat(2),
                12);
        customize.accept(annex);
        return bytes;
    }

    private static M2IdleAnimation.Idle read(byte[] bytes) {
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        return M2IdleAnimation.read(AssetSource.bytes(bytes), model, Cancellation.NONE).join();
    }

    private static void refused(String code, Consumer<Annex> change, int version) {
        byte[] bytes = authored(version, change);
        M2ReadersTest.failure(code, () -> read(bytes));
    }

    private static void refused(String code, String message, Consumer<Annex> change, int version) {
        byte[] bytes = authored(version, change);
        var error = AssetTasks.unwrap(assertThrows(RuntimeException.class, () -> read(bytes)));
        var format = assertInstanceOf(ModelFormatException.class, error);
        assertEquals(code, format.code());
        assertEquals(message, format.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void readsLocalStandKeysForEveryBone(int version) {
        var idle = read(authored(version, annex -> {}));
        assertEquals(1, idle.sequenceIndex());
        assertEquals(1000, idle.durationMillis());
        assertArrayEquals(new long[] {500}, idle.globalSequenceMillis());
        assertEquals(2, idle.bones().size());
        assertEquals(5, idle.keyCount());
        assertTrue(idle.animated());
        var translation = idle.bones().get(0).translation();
        assertEquals(M2IdleAnimation.Interpolation.LINEAR, translation.interpolation());
        assertArrayEquals(new int[] {0, 1000}, translation.times());
        assertArrayEquals(new float[] {0, 0, 0, 10, 0, 0}, translation.values());
        var rotation = idle.bones().get(0).rotation();
        assertEquals(M2IdleAnimation.Interpolation.STEP, rotation.interpolation());
        assertArrayEquals(new float[] {0, 0, 0, 1}, rotation.values(), 1e-6f);
        assertNull(idle.bones().get(0).scale());
        var scale = idle.bones().get(1).scale();
        assertEquals(0, scale.globalSequence());
        assertArrayEquals(new float[] {1, 1, 1, 2, 2, 2}, scale.values());
        assertNull(idle.bones().get(1).translation());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void tracksWithoutStandKeysAreIdentity(int version) {
        // Walk keys only: slice 0 populated, Stand slice 1 empty.
        var idle =
                read(
                        authored(
                                version,
                                annex ->
                                        annex.keys(
                                                0,
                                                16,
                                                1,
                                                -1,
                                                2,
                                                0,
                                                new int[] {0},
                                                v -> v.putFloat(1).putFloat(2).putFloat(3),
                                                12)));
        assertNull(idle.bones().get(0).translation());
        // A single declared slice cannot cover Stand at index 1.
        var short_ =
                read(
                        authored(
                                version,
                                annex ->
                                        annex.keys(
                                                0,
                                                16,
                                                1,
                                                -1,
                                                1,
                                                0,
                                                new int[] {0},
                                                v -> v.putFloat(1).putFloat(2).putFloat(3),
                                                12)));
        assertNull(short_.bones().get(0).translation());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void unsupportedProfilesAreExplicit(int version) {
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putShort(a.sequence(1), (short) 5), version);
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putShort(a.sequence(1) + 2, (short) 1), version);
        // An alias Stand whose target keeps its keys outside the model.
        refused(
                "MODEL_UNSUPPORTED",
                a -> {
                    a.buffer.putInt(a.sequence(1) + 12, 0x60);
                    a.buffer.putInt(a.sequence(0) + 12, 0);
                },
                version);
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putInt(a.sequence(1) + 12, 0), version);
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putInt(a.sequence(1) + 4, 0), version);
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putShort(a.track(0, 16), (short) 2), version);
        refused("MODEL_UNSUPPORTED", a -> a.buffer.putShort(a.track(0, 16), (short) 3), version);
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void inconsistentKeysAreInvalid(int version) {
        // Unordered key times.
        refused(
                "MODEL_INVALID",
                "Bone key times are unordered.",
                a ->
                        a.keys(
                                0,
                                16,
                                1,
                                -1,
                                2,
                                1,
                                new int[] {500, 100},
                                v ->
                                        v.putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0),
                                12),
                version);
        // A key beyond the Stand duration.
        refused(
                "MODEL_INVALID",
                "A bone key time lies beyond its sequence.",
                a ->
                        a.keys(
                                0,
                                16,
                                1,
                                -1,
                                2,
                                1,
                                new int[] {0, 1001},
                                v ->
                                        v.putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0)
                                                .putFloat(0),
                                12),
                version);
        // A global track key beyond its clock.
        refused(
                "MODEL_INVALID",
                "A bone key time lies beyond its sequence.",
                a ->
                        a.keys(
                                1,
                                56,
                                1,
                                0,
                                1,
                                0,
                                new int[] {0, 501},
                                v ->
                                        v.putFloat(1)
                                                .putFloat(1)
                                                .putFloat(1)
                                                .putFloat(1)
                                                .putFloat(1)
                                                .putFloat(1),
                                12),
                version);
        // An undeclared global sequence.
        refused("MODEL_INVALID", a -> a.buffer.putShort(a.track(1, 56) + 2, (short) 1), version);
        // Time and value slices disagree.
        refused("MODEL_INVALID", a -> a.buffer.putInt(a.track(0, 16) + 12, 1), version);
        // A zero-length compressed rotation key.
        refused(
                "MODEL_INVALID",
                a ->
                        a.keys(
                                0,
                                36,
                                0,
                                -1,
                                2,
                                1,
                                new int[] {0},
                                v ->
                                        v.putShort((short) 32767)
                                                .putShort((short) 32767)
                                                .putShort((short) 32767)
                                                .putShort((short) 32767),
                                8),
                version);
        // Key arrays outside the model body.
        refused("MODEL_INVALID", a -> a.buffer.putInt(a.track(0, 16) + 8, 0x7fff0000), version);
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void zeroLengthGlobalClocksAdmitKeysAtAnyTime(int version) {
        // A global sequence of length 0 never advances, so its keys are read in order wherever
        // they are stamped; the clock stays at 0 and holds the first key.
        var idle =
                read(
                        authored(
                                version,
                                a -> {
                                    a.buffer.putInt(a.at + GLOBALS, 0);
                                    a.keys(
                                            1,
                                            56,
                                            1,
                                            0,
                                            1,
                                            0,
                                            new int[] {3333, 3333, 6667},
                                            v -> {
                                                for (int i = 0; i < 9; i++) {
                                                    v.putFloat(1 + i / 3);
                                                }
                                            },
                                            12);
                                }));
        assertArrayEquals(new long[] {0}, idle.globalSequenceMillis());
        var scale = idle.bones().get(1).scale();
        assertEquals(0, scale.globalSequence());
        assertArrayEquals(new int[] {3333, 3333, 6667}, scale.times());
        assertArrayEquals(new float[] {1, 1, 1, 2, 2, 2, 3, 3, 3}, scale.values());
        // Order is still required on a zero-length clock.
        refused(
                "MODEL_INVALID",
                "Bone key times are unordered.",
                a -> {
                    a.buffer.putInt(a.at + GLOBALS, 0);
                    a.keys(
                            1,
                            56,
                            1,
                            0,
                            1,
                            0,
                            new int[] {6667, 3333},
                            v -> {
                                for (int i = 0; i < 6; i++) {
                                    v.putFloat(1);
                                }
                            },
                            12);
                },
                version);
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void keyStampsAboveTheIntRangeAreRefusedAsOutOfRange(int version) {
        // A zero-length clock has no sequence bound, but stamps are int milliseconds.
        refused(
                "MODEL_INVALID",
                "A bone key time is outside the supported key range.",
                a -> {
                    a.buffer.putInt(a.at + GLOBALS, 0);
                    a.keys(
                            1,
                            56,
                            1,
                            0,
                            1,
                            0,
                            new int[] {0, Integer.MIN_VALUE},
                            v -> {
                                for (int i = 0; i < 6; i++) {
                                    v.putFloat(1);
                                }
                            },
                            12);
                },
                version);
        // The largest int stamp is still admitted there.
        var idle =
                read(
                        authored(
                                version,
                                a -> {
                                    a.buffer.putInt(a.at + GLOBALS, 0);
                                    a.keys(
                                            1,
                                            56,
                                            1,
                                            0,
                                            1,
                                            0,
                                            new int[] {Integer.MAX_VALUE},
                                            v -> v.putFloat(1).putFloat(1).putFloat(1),
                                            12);
                                }));
        assertArrayEquals(new int[] {Integer.MAX_VALUE}, idle.bones().get(1).scale().times());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void aliasStandPlaysTheSequenceItsChainEndsAt(int version) {
        // Stand (index 1) aliases Walk (index 0): Walk's keys and duration play.
        var idle =
                read(
                        authored(
                                version,
                                a -> {
                                    a.buffer.putInt(a.sequence(1) + 12, 0x60);
                                    a.buffer.putShort(a.sequence(1) + 62, (short) 0);
                                    a.keys(
                                            0,
                                            16,
                                            1,
                                            -1,
                                            2,
                                            0,
                                            new int[] {0, 800},
                                            v ->
                                                    v.putFloat(0)
                                                            .putFloat(0)
                                                            .putFloat(0)
                                                            .putFloat(0)
                                                            .putFloat(4)
                                                            .putFloat(0),
                                            12);
                                }));
        assertEquals(0, idle.sequenceIndex());
        assertEquals(800, idle.durationMillis());
        var translation = idle.bones().get(0).translation();
        assertArrayEquals(new int[] {0, 800}, translation.times());
        assertArrayEquals(new float[] {0, 0, 0, 0, 4, 0}, translation.values());
        // Walk's rotation track has no slice 0 keys; Stand's own slice is not used.
        assertNull(idle.bones().get(0).rotation());
        // Global tracks keep their own clock whichever sequence plays.
        assertEquals(0, idle.bones().get(1).scale().globalSequence());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void brokenAliasChainsAreInvalid(int version) {
        // Self alias.
        refused(
                "MODEL_INVALID",
                "The Stand sequence alias chain contains a cycle.",
                a -> {
                    a.buffer.putInt(a.sequence(1) + 12, 0x60);
                    a.buffer.putShort(a.sequence(1) + 62, (short) 1);
                },
                version);
        // Two sequences aliasing each other.
        refused(
                "MODEL_INVALID",
                "The Stand sequence alias chain contains a cycle.",
                a -> {
                    a.buffer.putInt(a.sequence(1) + 12, 0x60);
                    a.buffer.putShort(a.sequence(1) + 62, (short) 0);
                    a.buffer.putInt(a.sequence(0) + 12, 0x60);
                    a.buffer.putShort(a.sequence(0) + 62, (short) 1);
                },
                version);
        // An alias past the declared sequences.
        refused(
                "MODEL_INVALID",
                "The Stand sequence aliases an undeclared sequence.",
                a -> {
                    a.buffer.putInt(a.sequence(1) + 12, 0x60);
                    a.buffer.putShort(a.sequence(1) + 62, (short) 2);
                },
                version);
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void sourceMustMatchTheParsedModel(int version) {
        byte[] bytes = authored(version, a -> {});
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        byte[] changed = bytes.clone();
        var buffer = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
        int base = Math.toIntExact(model.baseOffset());
        buffer.putInt(base + 28, 1);
        M2ReadersTest.failure(
                "MODEL_INVALID",
                () ->
                        M2IdleAnimation.read(AssetSource.bytes(changed), model, Cancellation.NONE)
                                .join());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void externalSkeletonsAreUnsupported(int version) {
        byte[] bytes = authored(version, a -> {});
        byte[] extended = java.util.Arrays.copyOf(bytes, bytes.length + 12);
        var buffer = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(bytes.length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(bytes.length + 4, 4);
        buffer.putInt(bytes.length + 8, 123456);
        var model = M2ModelReader.read(AssetSource.bytes(extended)).join();
        assertEquals(123456, model.skeletonFileDataId());
        M2ReadersTest.failure(
                "MODEL_UNSUPPORTED",
                () ->
                        M2IdleAnimation.read(AssetSource.bytes(extended), model, Cancellation.NONE)
                                .join());
    }

    private static byte[] withBoneFiles(byte[] bytes, int... ids) {
        byte[] extended = java.util.Arrays.copyOf(bytes, bytes.length + 8 + ids.length * 4);
        var buffer = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(bytes.length, "BFID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(bytes.length + 4, ids.length * 4);
        for (int i = 0; i < ids.length; i++) {
            buffer.putInt(bytes.length + 8 + i * 4, ids[i]);
        }
        return extended;
    }

    private static M2IdleAnimation.StandFacts facts(byte[] bytes) {
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        return M2IdleAnimation.assessStand(AssetSource.bytes(bytes), model, Cancellation.NONE)
                .join();
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void boneFilesRefusePlaybackButStandFactsAreStillReported(int version) {
        byte[] bytes = withBoneFiles(authored(version, a -> {}), 1001, 1002);
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        assertEquals(java.util.List.of(1001L, 1002L), model.boneFileDataIds());
        M2ReadersTest.failure("MODEL_UNSUPPORTED", () -> read(bytes));
        var facts = facts(bytes);
        assertNull(facts.refusal());
        assertEquals(1, facts.sequenceIndex());
        assertEquals(1000, facts.durationMillis());
        assertEquals(5, facts.keyCount());
        assertEquals(2, facts.animatedBones());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void standFactsCarryRefusalsButStructuralErrorsStillFail(int version) {
        var curve =
                facts(
                        withBoneFiles(
                                authored(
                                        version, a -> a.buffer.putShort(a.track(0, 16), (short) 2)),
                                1001));
        assertEquals(-1, curve.sequenceIndex());
        assertTrue(curve.refusal().contains("curve interpolation"));
        var external =
                facts(
                        withBoneFiles(
                                authored(version, a -> a.buffer.putInt(a.sequence(1) + 12, 0)),
                                1001));
        assertTrue(external.refusal().contains("external animation file"));
        byte[] unordered =
                withBoneFiles(
                        authored(
                                version,
                                a ->
                                        a.keys(
                                                0,
                                                16,
                                                1,
                                                -1,
                                                2,
                                                1,
                                                new int[] {500, 100},
                                                v ->
                                                        v.putFloat(0)
                                                                .putFloat(0)
                                                                .putFloat(0)
                                                                .putFloat(0)
                                                                .putFloat(0)
                                                                .putFloat(0),
                                                12)),
                        1001);
        M2ReadersTest.failure("MODEL_INVALID", () -> facts(unordered));
    }
}
