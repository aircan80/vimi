package com.vibeminer.formats.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/** Complete single-stream Ogg framing/CRC preflight before entering the isolated codec. */
final class OggVoiceInput {
    private static final int[] CRC = crcTable();

    private OggVoiceInput() {}

    static void validate(byte[] bytes, BooleanSupplier canceled) throws IOException {
        int offset = 0, sequence = 0, serial = 0;
        boolean ended = false, continued = false;
        while (offset < bytes.length) {
            if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            if (ended
                    || bytes.length - offset < 27
                    || bytes[offset] != 'O'
                    || bytes[offset + 1] != 'g'
                    || bytes[offset + 2] != 'g'
                    || bytes[offset + 3] != 'S'
                    || bytes[offset + 4] != 0) {
                throw invalid();
            }
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int flags = Byte.toUnsignedInt(bytes[offset + 5]),
                    segments = Byte.toUnsignedInt(bytes[offset + 26]);
            if ((flags & ~7) != 0
                    || ((flags & 1) != 0) != continued
                    || ((flags & 2) != 0) != (sequence == 0)
                    || bytes.length - offset < 27 + segments) {
                throw invalid();
            }
            int size = 27 + segments;
            for (int i = 0; i < segments; i++) {
                size += Byte.toUnsignedInt(bytes[offset + 27 + i]);
            }
            if (size > bytes.length - offset || input.getInt(offset + 18) != sequence) {
                throw invalid();
            }
            if (sequence == 0) {
                serial = input.getInt(offset + 14);
                int header = offset + 27 + segments;
                if (segments != 1
                        || Byte.toUnsignedInt(bytes[offset + 27]) != 30
                        || size < 27 + segments + 30
                        || bytes[header] != 1
                        || bytes[header + 1] != 'v'
                        || bytes[header + 2] != 'o'
                        || bytes[header + 3] != 'r'
                        || bytes[header + 4] != 'b'
                        || bytes[header + 5] != 'i'
                        || bytes[header + 6] != 's') {
                    throw invalid();
                }
                int rate = input.getInt(header + 12),
                        channels = Byte.toUnsignedInt(bytes[header + 11]);
                if (input.getInt(header + 7) != 0
                        || rate < 8000
                        || rate > 48000
                        || channels < 1
                        || channels > 2) {
                    throw invalid();
                }
            } else if (input.getInt(offset + 14) != serial) {
                throw invalid();
            }
            int crc = 0;
            for (int i = 0; i < size; i++) {
                int value = i >= 22 && i < 26 ? 0 : Byte.toUnsignedInt(bytes[offset + i]);
                crc = (crc << 8) ^ CRC[((crc >>> 24) ^ value) & 255];
            }
            if (crc != input.getInt(offset + 22)) {
                throw invalid();
            }
            continued =
                    segments > 0 && Byte.toUnsignedInt(bytes[offset + 27 + segments - 1]) == 255;
            ended = (flags & 4) != 0;
            if (ended && continued) {
                throw invalid();
            }
            offset += size;
            sequence++;
        }
        if (!ended) {
            throw invalid();
        }
    }

    private static IOException invalid() {
        return new IOException("Voice preview requires a complete valid single Vorbis stream");
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value << 1) ^ ((value & 0x80000000) != 0 ? 0x04c11db7 : 0);
            }
            table[i] = value;
        }
        return table;
    }
}
