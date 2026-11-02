package com.vibeminer.formats.db2;

import com.vibeminer.formats.definitions.DbdDefinitionReader;
import com.vibeminer.formats.definitions.DbdDocument;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Authored tiny DB2 writer, independent of the reader. */
public final class Db2Fixture {
    private Db2Fixture() {}

    public static DbdDocument definition(String columns, String fields) {
        return DbdDefinitionReader.parse(
                ("COLUMNS\n" + columns + "\n\nLAYOUT 01020304\nBUILD 1.2.3.4\n" + fields + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] numeric(
            String format, int kind, int width, long raw, long[] palette, boolean copy) {
        int header = format.equals("WDC5") ? 204 : 72, at = header - 68;
        int aux = palette.length * 4, start = header + 40 + 28 + aux;
        int size = 8 + 4 + (copy ? 8 : 0);
        var b = ByteBuffer.allocate(start + size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(format.getBytes(StandardCharsets.US_ASCII));
        if (header == 204) {
            b.putInt(5);
        }
        b.putInt(at, 1);
        b.putInt(at + 4, 1);
        b.putInt(at + 8, 8);
        b.putInt(at + 16, 0x11223344);
        b.putInt(at + 20, 0x01020304);
        b.putShort(at + 36, (short) 4);
        b.putInt(at + 40, 1);
        b.putInt(at + 52, 24);
        b.putInt(at + 60, aux);
        b.putInt(at + 64, 1);
        b.position(header);
        b.putLong(0)
                .putInt(start)
                .putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(4)
                .putInt(0)
                .putInt(0)
                .putInt(copy ? 1 : 0);
        b.putShort((short) (32 - width)).putShort((short) 0);
        b.putShort((short) 0)
                .putShort((short) width)
                .putInt(aux)
                .putInt(kind)
                .putInt(0)
                .putInt(kind == 0 ? 0 : width)
                .putInt(0);
        for (long p : palette) {
            b.putInt((int) p);
        }
        b.position(start);
        b.putLong(raw).putInt(7);
        if (copy) {
            b.putInt(8).putInt(7);
        }
        return b.array();
    }

    public static byte[] sparse() {
        int start = 204 + 40 + 56;
        byte[] record = "café\0".getBytes(StandardCharsets.UTF_8);
        int width = (record.length + 4 + 3) & ~3;
        var b = ByteBuffer.allocate(start + width + 14).order(ByteOrder.LITTLE_ENDIAN);
        b.put("WDC5".getBytes(StandardCharsets.US_ASCII)).putInt(5);
        b.putInt(136, 1);
        b.putInt(140, 2);
        b.putInt(144, 8);
        b.putInt(152, 0x11223344);
        b.putInt(156, 0x01020304);
        b.putShort(172, (short) 5);
        b.putInt(176, 2);
        b.putInt(188, 48);
        b.putInt(200, 1);
        b.position(204);
        b.putLong(0)
                .putInt(start)
                .putInt(1)
                .putInt(0)
                .putInt(start + width)
                .putInt(4)
                .putInt(0)
                .putInt(1)
                .putInt(0);
        b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 4);
        for (int offset : new int[] {0, 32}) {
            b.putShort((short) offset)
                    .putShort((short) 32)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0);
        }
        b.position(start);
        b.put(record).putInt(123);
        b.position(start + width);
        b.putInt(7).putInt(start).putShort((short) width).putInt(7);
        return b.array();
    }

    public static byte[] trailing(byte[] original) {
        return Arrays.copyOf(original, original.length + 1);
    }
}
