package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class M2BindPoseFactsTest {
    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void ownTrackAbsenceIsDistinctFromNonemptyAndGlobalTrackMetadata(int version) {
        byte[] bytes = authoredModel(version);
        var initial = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        assertFalse(initial.bones().isEmpty());
        var declaration =
                initial.declarations().stream()
                        .filter(d -> d.name().equals("bones"))
                        .findFirst()
                        .orElseThrow();
        int start = Math.toIntExact(initial.baseOffset() + declaration.offset());
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int bone = 0; bone < initial.bones().size(); bone++) {
            for (int offset : new int[] {16, 36, 56}) {
                int track = start + bone * 88 + offset;
                buffer.putShort(track, (short) 0);
                buffer.putShort(track + 2, (short) -1);
                buffer.putInt(track + 4, 0);
                buffer.putInt(track + 8, 0);
                buffer.putInt(track + 12, 0);
                buffer.putInt(track + 16, 0);
            }
        }
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        var absent =
                M2BindPoseFacts.read(AssetSource.bytes(bytes), model, Cancellation.NONE).join();
        assertEquals(model, absent.model());
        assertEquals(model.bones().size(), absent.absentOwnTracks().size());
        assertTrue(absent.absentOwnTracks().stream().allMatch(Boolean::booleanValue));
        assertThrows(
                UnsupportedOperationException.class, () -> absent.absentOwnTracks().set(0, false));

        // A declared value array is not proof of absence, even without readable key data.
        buffer.putInt(start + 16 + 12, 1);
        var declared =
                M2BindPoseFacts.read(AssetSource.bytes(bytes), model, Cancellation.NONE).join();
        assertFalse(declared.absentOwnTracks().getFirst());
        buffer.putInt(start + 16 + 12, 0);
        buffer.putShort(start + 16 + 2, (short) 0);
        assertFalse(
                M2BindPoseFacts.read(AssetSource.bytes(bytes), model, Cancellation.NONE)
                        .join()
                        .absentOwnTracks()
                        .getFirst());
        // Ordinary ancestors having tracks is deliberately independent of another bone's facts.
        if (model.bones().size() > 1) {
            assertTrue(declared.absentOwnTracks().get(1));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {272, 274})
    void mismatchedSourceCannotPublishFactsForParsedBoneMetadata(int version) {
        byte[] bytes = authoredModel(version);
        var model = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        var declaration =
                model.declarations().stream()
                        .filter(d -> d.name().equals("bones"))
                        .findFirst()
                        .orElseThrow();
        int start = Math.toIntExact(model.baseOffset() + declaration.offset());
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(start, buffer.getInt(start) + 1);
        M2ReadersTest.failure(
                "MODEL_INVALID",
                () ->
                        M2BindPoseFacts.read(AssetSource.bytes(bytes), model, Cancellation.NONE)
                                .join());
        M2ReadersTest.failure(
                "MODEL_INVALID",
                () ->
                        M2BindPoseFacts.read(
                                        AssetSource.bytes(
                                                java.util.Arrays.copyOf(bytes, bytes.length - 1)),
                                        model,
                                        Cancellation.NONE)
                                .join());
    }

    private static byte[] authoredModel(int version) {
        byte[] original = M2ReadersTest.golden("model-" + version + ".m2");
        var parsed = M2ModelReader.read(AssetSource.bytes(original)).join();
        int base = Math.toIntExact(parsed.baseOffset());
        int bodyBytes = Math.toIntExact(parsed.baseBytes());
        int insertion = base + bodyBytes;
        byte[] bytes = new byte[original.length + 176];
        System.arraycopy(original, 0, bytes, 0, insertion);
        System.arraycopy(original, insertion, bytes, insertion + 176, original.length - insertion);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(base - 4, bodyBytes + 176);
        buffer.putInt(base + 44, 2);
        buffer.putInt(base + 48, bodyBytes);
        for (int bone = 0; bone < 2; bone++) {
            int row = insertion + bone * 88;
            buffer.putInt(row, -1);
            buffer.putInt(row + 4, bone == 0 ? 0x200 : 8);
            buffer.putShort(row + 8, (short) (bone - 1));
            buffer.putFloat(row + 76, bone + 1);
            buffer.putFloat(row + 80, bone + 2);
            buffer.putFloat(row + 84, bone + 3);
            for (int track : new int[] {16, 36, 56}) {
                buffer.putShort(row + track + 2, (short) -1);
            }
        }
        return bytes;
    }
}
