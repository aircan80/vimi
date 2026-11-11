package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.AssetSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class M2AttachmentsTest {
    /** The authored two-bone model with one attachment record at the end of its annex. */
    private static byte[] withAttachment(int id, int bone) {
        byte[] bytes = M2IdleAnimationTest.authored(274, annex -> {});
        var parsed = M2ModelReader.read(AssetSource.bytes(bytes)).join();
        int base = Math.toIntExact(parsed.baseOffset());
        int body = Math.toIntExact(parsed.baseBytes());
        int at = body - 48;
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(base + 240, 1).putInt(base + 244, at);
        int row = base + at;
        buffer.putInt(row, id).putShort(row + 4, (short) bone).putShort(row + 6, (short) 0);
        buffer.putFloat(row + 8, 0.25f).putFloat(row + 12, -0.5f).putFloat(row + 16, 1.75f);
        return bytes;
    }

    @Test
    void attachmentsKeepTheirIdBoneAndRestPosition() {
        assertEquals(
                List.of(),
                M2ModelReader.read(AssetSource.bytes(withoutAttachments())).join().attachments());
        var model = M2ModelReader.read(AssetSource.bytes(withAttachment(11, 1))).join();
        assertEquals(
                List.of(new M2Types.Attachment(11, 1, new M2Types.Vec3(0.25f, -0.5f, 1.75f))),
                model.attachments());
    }

    @Test
    void anAttachmentOnABoneTheModelLacksIsInvalid() {
        var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        RuntimeException.class,
                        () -> M2ModelReader.read(AssetSource.bytes(withAttachment(11, 2))).join());
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof ModelFormatException, String.valueOf(cause));
        assertEquals("MODEL_INVALID", ((ModelFormatException) cause).code());
    }

    private static byte[] withoutAttachments() {
        return M2IdleAnimationTest.authored(274, annex -> {});
    }
}
