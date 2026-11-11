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

    /** Sequence rows with only the alias flag (0x40) and alias index set; -1 is not an alias. */
    private static java.util.List<byte[]> aliasRows(int... next) {
        var rows = new java.util.ArrayList<byte[]>();
        for (int target : next) {
            var row = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            row.putInt(12, target < 0 ? 0x20 : 0x60);
            row.putShort(62, (short) Math.max(target, 0));
            rows.add(row.array());
        }
        return rows;
    }

    @org.junit.jupiter.api.Test
    void aliasTargetFollowsTheWholeChain() {
        // 0 -> 1 -> 2: two hops end at the first row without the alias flag.
        assertEquals(2, M2IdleAnimation.aliasTarget(aliasRows(1, 2, -1), 0));
        // 2 -> 0 -> 1 in a different order.
        assertEquals(1, M2IdleAnimation.aliasTarget(aliasRows(1, -1, 0), 2));
        // A row without the alias flag is its own target.
        assertEquals(2, M2IdleAnimation.aliasTarget(aliasRows(1, 2, -1), 2));
        // A three-row cycle.
        aliasRefused("The Stand sequence alias chain contains a cycle.", aliasRows(1, 2, 0));
        // An alias index equal to the row count.
        aliasRefused("The Stand sequence aliases an undeclared sequence.", aliasRows(3, 2, -1));
    }

    private static void aliasRefused(String message, java.util.List<byte[]> rows) {
        var error =
                assertThrows(
                        ModelFormatException.class, () -> M2IdleAnimation.aliasTarget(rows, 0));
        assertEquals("MODEL_INVALID", error.code());
        assertEquals(message, error.getMessage());
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
        var error =
                assertInstanceOf(
                        ModelFormatException.class,
                        AssetTasks.unwrap(assertThrows(RuntimeException.class, () -> read(bytes))));
        assertEquals("MODEL_UNSUPPORTED", error.code());
        assertEquals(
                "The model uses an external skeleton or bone file; idle evaluation reads only"
                        + " bones stored in the model.",
                error.getMessage());
        var facts = facts(bytes);
        assertNull(facts.refusal());
        assertEquals(1, facts.sequenceIndex());
        assertEquals(1000, facts.durationMillis());
        assertEquals(5, facts.keyCount());
        assertEquals(2, facts.animatedBones());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void baseRigReadsTheStandOfModelsWithBoneFiles(int version) {
        byte[] bytes = withBoneFiles(authored(version, a -> {}), 1001, 1002);
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        var idle =
                M2IdleAnimation.readBaseRig(AssetSource.bytes(bytes), model, Cancellation.NONE)
                        .join();
        // The same Stand as the model without bone files.
        var plain = read(authored(version, a -> {}));
        assertEquals(plain.sequenceIndex(), idle.sequenceIndex());
        assertEquals(plain.durationMillis(), idle.durationMillis());
        assertEquals(plain.keyCount(), idle.keyCount());
        assertArrayEquals(
                plain.bones().get(0).translation().values(),
                idle.bones().get(0).translation().values());
        // The base rig still applies every other check.
        byte[] curve =
                withBoneFiles(
                        authored(version, a -> a.buffer.putShort(a.track(0, 16), (short) 2)), 1001);
        var curveModel = M2ModelReader.read(AssetSource.bytes(curve)).join();
        M2ReadersTest.failure(
                "MODEL_UNSUPPORTED",
                () ->
                        M2IdleAnimation.readBaseRig(
                                        AssetSource.bytes(curve), curveModel, Cancellation.NONE)
                                .join());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void baseRigStillRefusesExternalSkeletons(int version) {
        byte[] bytes = authored(version, a -> {});
        byte[] extended = java.util.Arrays.copyOf(bytes, bytes.length + 12);
        var buffer = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(bytes.length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(bytes.length + 4, 4);
        buffer.putInt(bytes.length + 8, 123456);
        for (byte[] source : new byte[][] {extended, withBoneFiles(extended, 1001)}) {
            var model = M2ModelReader.read(AssetSource.bytes(source)).join();
            var error =
                    AssetTasks.unwrap(
                            assertThrows(
                                    RuntimeException.class,
                                    () ->
                                            M2IdleAnimation.readBaseRig(
                                                            AssetSource.bytes(source),
                                                            model,
                                                            Cancellation.NONE)
                                                    .join()));
            var format = assertInstanceOf(ModelFormatException.class, error);
            assertEquals("MODEL_UNSUPPORTED", format.code());
            assertEquals(
                    "The model uses an external skeleton; idle evaluation reads only bones stored"
                            + " in the model.",
                    format.getMessage());
        }
    }

    private static byte[] skeletonChunk(String id, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /** A track header at {@code row}: interpolation, clock, one key slice array each. */
    private static void skeletonTrack(
            ByteBuffer b,
            int row,
            int interpolation,
            int global,
            int slices,
            int times,
            int values) {
        b.putShort(row, (short) interpolation).putShort(row + 2, (short) global);
        b.putInt(row + 4, slices).putInt(row + 8, times);
        b.putInt(row + 12, slices).putInt(row + 16, values);
    }

    /**
     * An authored skeleton: global clock 500, Walk then Stand (1000 ms), two bones. Bone 0
     * translates linearly on Stand (slice 1); bone 1 scales on global clock 0. Every array offset
     * inside SKB1 counts from the start of the SKB1 data.
     */
    static byte[] skeleton(Consumer<ByteBuffer> sks1, Consumer<ByteBuffer> skb1, int parent) {
        var s = ByteBuffer.allocate(32 + 4 + 128).order(ByteOrder.LITTLE_ENDIAN);
        s.putInt(0, 1).putInt(4, 32).putInt(8, 2).putInt(12, 36);
        s.putInt(32, 500);
        s.putShort(36, (short) 4).putInt(40, 800).putInt(48, 0x20);
        s.putShort(100, (short) 0).putInt(104, 1000).putInt(112, 0x20);
        sks1.accept(s);
        var b = ByteBuffer.allocate(304).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 2).putInt(4, 16);
        for (int bone = 0; bone < 2; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, -1).putShort(row + 8, (short) (bone - 1)).putFloat(row + 80, bone);
            for (int track : new int[] {16, 36, 56}) {
                b.putShort(row + track + 2, (short) -1);
            }
        }
        // Bone 0 translation: two slices, keys in slice 1 only.
        skeletonTrack(b, 16 + 16, 1, -1, 2, 192, 208);
        b.putInt(192 + 8, 2).putInt(192 + 12, 224);
        b.putInt(208 + 8, 2).putInt(208 + 12, 232);
        b.putInt(224, 0).putInt(228, 1000);
        b.putFloat(232, 0).putFloat(236, 0).putFloat(240, 0);
        b.putFloat(244, 10).putFloat(248, 0).putFloat(252, 0);
        // Bone 1 scale: one slice on global clock 0.
        skeletonTrack(b, 16 + 88 + 56, 1, 0, 1, 256, 264);
        b.putInt(256, 2).putInt(260, 272);
        b.putInt(264, 2).putInt(268, 280);
        b.putInt(272, 0).putInt(276, 500);
        for (int i = 0; i < 6; i++) {
            b.putFloat(280 + i * 4, i < 3 ? 1 : 2);
        }
        skb1.accept(b);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(skeletonChunk("SKL1", new byte[16]));
        out.writeBytes(skeletonChunk("SKS1", s.array()));
        out.writeBytes(skeletonChunk("SKB1", b.array()));
        if (parent != 0) {
            var pd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(8, parent);
            out.writeBytes(skeletonChunk("SKPD", pd.array()));
        }
        return out.toByteArray();
    }

    /** The golden model (no bones of its own) naming an external skeleton. */
    static byte[] skeletonModel(int version) {
        byte[] bytes = M2ReadersTest.golden("model-" + version + ".m2");
        byte[] extended = java.util.Arrays.copyOf(bytes, bytes.length + 12);
        var buffer = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(bytes.length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(bytes.length + 4, 4);
        buffer.putInt(bytes.length + 8, 123456);
        return extended;
    }

    private static M2IdleAnimation.Idle readSkeleton(int version, byte[] skeletonBytes) {
        var model = M2ModelReader.read(AssetSource.bytes(skeletonModel(version))).join();
        var source = AssetSource.bytes(skeletonBytes);
        var skeleton = M2SkeletonFile.read(source, Cancellation.NONE).join();
        return M2IdleAnimation.readSkeleton(source, skeleton, model, Cancellation.NONE).join();
    }

    private static void skeletonRefused(
            int version, String code, String message, byte[] skeletonBytes) {
        var error =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () -> readSkeleton(version, skeletonBytes)));
        var format = assertInstanceOf(ModelFormatException.class, error);
        assertEquals(code, format.code());
        assertEquals(message, format.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void skeletonStandReadsTheSkeletonsBonesSequencesAndTracks(int version) {
        assertEquals(
                0,
                M2ModelReader.read(AssetSource.bytes(skeletonModel(version)))
                        .join()
                        .bones()
                        .size());
        var idle = readSkeleton(version, skeleton(s -> {}, b -> {}, 0));
        assertEquals(2, idle.rig().size());
        assertEquals(0, idle.rig().get(1).parent());
        assertEquals(1, idle.rig().get(1).pivot().y());
        assertEquals(1, idle.sequenceIndex());
        assertEquals(1000, idle.durationMillis());
        assertArrayEquals(new long[] {500}, idle.globalSequenceMillis());
        assertEquals(4, idle.keyCount());
        var translation = idle.bones().get(0).translation();
        assertArrayEquals(new int[] {0, 1000}, translation.times());
        assertArrayEquals(new float[] {0, 0, 0, 10, 0, 0}, translation.values());
        var scale = idle.bones().get(1).scale();
        assertEquals(0, scale.globalSequence());
        assertArrayEquals(new float[] {1, 1, 1, 2, 2, 2}, scale.values());
        assertNull(idle.bones().get(0).rotation());
        // A parent skeleton is not needed when the skeleton has its own bones and Stand.
        assertEquals(2, readSkeleton(version, skeleton(s -> {}, b -> {}, 777)).rig().size());
        // An alias Stand in the skeleton plays its target: Walk has no translation keys.
        var alias =
                readSkeleton(
                        version,
                        skeleton(s -> s.putInt(112, 0x60).putShort(162, (short) 0), b -> {}, 0));
        assertEquals(0, alias.sequenceIndex());
        assertEquals(800, alias.durationMillis());
        assertNull(alias.bones().get(0).translation());
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void skeletonStandRefusals(int version) {
        // Track arrays count from the SKB1 data and may not start inside its 16-byte header.
        skeletonRefused(
                version,
                "MODEL_INVALID",
                "The bone track times span lies outside its skeleton section or overlaps its"
                        + " header.",
                skeleton(s -> {}, b -> b.putInt(16 + 16 + 8, 8), 0));
        skeletonRefused(
                version,
                "MODEL_INVALID",
                "The bone track times span lies outside its skeleton section or overlaps its"
                        + " header.",
                skeleton(s -> {}, b -> b.putInt(16 + 16 + 8, 300), 0));
        // Without bones of its own, a child skeleton would need its parent.
        skeletonRefused(
                version,
                "MODEL_UNSUPPORTED",
                "The skeleton keeps its bones or sequences in its parent skeleton, which idle"
                        + " evaluation does not read.",
                skeleton(s -> {}, b -> b.putInt(0, 0), 777));
        skeletonRefused(
                version,
                "MODEL_UNSUPPORTED",
                "The skeleton has no bones or sequences to animate.",
                skeleton(s -> {}, b -> b.putInt(0, 0), 0));
        skeletonRefused(
                version,
                "MODEL_UNSUPPORTED",
                "The skeleton records no Stand sequence (ID 0, variation 0).",
                skeleton(s -> s.putShort(100, (short) 5), b -> {}, 0));
        skeletonRefused(
                version,
                "MODEL_UNSUPPORTED",
                "Stand keys are stored in an external animation file, which idle evaluation does"
                        + " not read.",
                skeleton(s -> s.putInt(112, 0), b -> {}, 0));
        skeletonRefused(
                version,
                "MODEL_INVALID",
                "Bone key times are unordered.",
                skeleton(s -> {}, b -> b.putInt(228, 100).putInt(224, 900), 0));
    }

    /** A skeleton of {@code count} root bones without tracks, and one Stand. */
    private static byte[] rootBonesSkeleton(int count) {
        var b = ByteBuffer.allocate(16 + count * 88).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, count).putInt(4, 16);
        for (int bone = 0; bone < count; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, -1).putShort(row + 8, (short) -1);
            for (int track : new int[] {16, 36, 56}) {
                b.putShort(row + track + 2, (short) -1);
            }
        }
        var s = ByteBuffer.allocate(32 + 64).order(ByteOrder.LITTLE_ENDIAN);
        s.putInt(8, 1).putInt(12, 32).putInt(32 + 4, 1000).putInt(32 + 12, 0x20);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(skeletonChunk("SKL1", new byte[16]));
        out.writeBytes(skeletonChunk("SKS1", s.array()));
        out.writeBytes(skeletonChunk("SKB1", b.array()));
        return out.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void skeletonStandAdmitsBonesUpToTheEvaluationBudget(int version) {
        int over = M2IdleAnimation.MAX_BONES + 1;
        skeletonRefused(
                version,
                "MODEL_UNSUPPORTED",
                "The skeleton has "
                        + over
                        + " bones; idle evaluation admits at most "
                        + M2IdleAnimation.MAX_BONES
                        + " bones.",
                rootBonesSkeleton(over));
        // More bones than an 8-bit vertex index can name: the rig is still read whole.
        var idle = readSkeleton(version, rootBonesSkeleton(345));
        assertEquals(345, idle.rig().size());
        assertEquals(345, idle.bones().size());
    }

    /** One root bone whose Stand translation (10 s) has {@code keys} linear keys, 1 ms apart. */
    private static byte[] denseTrackSkeleton(int keys) {
        int data = 16 + 88, times = data + 16, values = times + keys * 4;
        var b = ByteBuffer.allocate(values + keys * 12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 1).putInt(4, 16);
        b.putInt(16, -1).putShort(16 + 8, (short) -1);
        for (int track : new int[] {16, 36, 56}) {
            b.putShort(16 + track + 2, (short) -1);
        }
        skeletonTrack(b, 16 + 16, 1, -1, 1, data, data + 8);
        b.putInt(data, keys).putInt(data + 4, times);
        b.putInt(data + 8, keys).putInt(data + 12, values);
        for (int k = 0; k < keys; k++) {
            b.putInt(times + k * 4, k);
            b.putFloat(values + k * 12 + 8, k * 0.001f);
        }
        var s = ByteBuffer.allocate(32 + 64).order(ByteOrder.LITTLE_ENDIAN);
        s.putInt(8, 1).putInt(12, 32).putInt(32 + 4, 100_000).putInt(32 + 12, 0x20);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(skeletonChunk("SKL1", new byte[16]));
        out.writeBytes(skeletonChunk("SKS1", s.array()));
        out.writeBytes(skeletonChunk("SKB1", b.array()));
        return out.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void oneDenselyKeyedTrackIsReadUpToTheTrackBudget(int version) {
        // Above the former 4,096-key track bound.
        var idle = readSkeleton(version, denseTrackSkeleton(18_029));
        assertEquals(18_029, idle.keyCount());
        assertEquals(18_029, idle.bones().getFirst().translation().keyCount());
        skeletonRefused(
                version,
                "MODEL_LIMIT",
                "Stand bone keys exceed the idle evaluation budget.",
                denseTrackSkeleton(M2IdleAnimation.MAX_KEYS_PER_TRACK + 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void skeletonStandNeedsAModelWithoutBonesAndTheMatchingSource(int version) {
        byte[] skeletonBytes = skeleton(s -> {}, b -> {}, 0);
        var skeleton =
                M2SkeletonFile.read(AssetSource.bytes(skeletonBytes), Cancellation.NONE).join();
        // A model with bones of its own beside SKID.
        byte[] own = authored(version, a -> {});
        byte[] withSkid = java.util.Arrays.copyOf(own, own.length + 12);
        var buffer = ByteBuffer.wrap(withSkid).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(own.length, "SKID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(own.length + 4, 4).putInt(own.length + 8, 123456);
        var bonesModel = M2ModelReader.read(AssetSource.bytes(withSkid)).join();
        var error =
                assertInstanceOf(
                        ModelFormatException.class,
                        AssetTasks.unwrap(
                                assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                M2IdleAnimation.readSkeleton(
                                                                AssetSource.bytes(skeletonBytes),
                                                                skeleton,
                                                                bonesModel,
                                                                Cancellation.NONE)
                                                        .join())));
        assertEquals(
                "The model stores bones of its own beside its external skeleton.",
                error.getMessage());
        // A source other than the one the skeleton was read from.
        var model = M2ModelReader.read(AssetSource.bytes(skeletonModel(version))).join();
        byte[] longer = java.util.Arrays.copyOf(skeletonBytes, skeletonBytes.length + 4);
        var mismatch =
                assertInstanceOf(
                        ModelFormatException.class,
                        AssetTasks.unwrap(
                                assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                M2IdleAnimation.readSkeleton(
                                                                AssetSource.bytes(longer),
                                                                skeleton,
                                                                model,
                                                                Cancellation.NONE)
                                                        .join())));
        assertEquals("Animation source differs from the parsed skeleton.", mismatch.getMessage());
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
