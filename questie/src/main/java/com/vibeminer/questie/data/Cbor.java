package com.vibeminer.questie.data;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Definite-length, bounded CBOR data reader. It never evaluates Lua or CBOR tags. */
final class Cbor {
    private final ByteBuffer input;
    private int nodes;

    private Cbor(byte[] bytes) {
        if (bytes.length > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("CBOR byte limit");
        }
        input = ByteBuffer.wrap(bytes);
    }

    static Object decode(byte[] bytes) {
        var decoder = new Cbor(bytes);
        var value = decoder.read(0);
        if (decoder.input.hasRemaining()) {
            throw new IllegalArgumentException("Trailing CBOR");
        }
        return value;
    }

    private int next() {
        if (!input.hasRemaining()) {
            throw new IllegalArgumentException("Truncated CBOR");
        }
        return Byte.toUnsignedInt(input.get());
    }

    private long argument(int additional) {
        if (additional < 24) {
            return additional;
        }
        int count =
                switch (additional) {
                    case 24 -> 1;
                    case 25 -> 2;
                    case 26 -> 4;
                    case 27 -> 8;
                    default -> throw new IllegalArgumentException("Indefinite/reserved CBOR");
                };
        long value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 8) | next();
        }
        if (value < 0) {
            throw new IllegalArgumentException("CBOR unsigned integer overflow");
        }
        return value;
    }

    private int length(long n) {
        if (n > 2_000_000) {
            throw new IllegalArgumentException("CBOR collection limit");
        }
        return (int) n;
    }

    private byte[] bytes(long count) {
        if (count > input.remaining() || count > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("CBOR string limit/truncation");
        }
        var bytes = new byte[(int) count];
        input.get(bytes);
        return bytes;
    }

    private Object read(int depth) {
        if (depth > 64 || ++nodes > 10_000_000) {
            throw new IllegalArgumentException("CBOR complexity limit");
        }
        int initial = next();
        int major = initial >>> 5;
        int additional = initial & 31;
        if (major == 7) {
            return switch (additional) {
                case 20 -> false;
                case 21 -> true;
                case 22 -> null;
                case 25 -> half((next() << 8) | next());
                case 26 ->
                        finite(
                                Float.intBitsToFloat(
                                        (next() << 24) | (next() << 16) | (next() << 8) | next()));
                case 27 -> {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) {
                        bits = (bits << 8) | next();
                    }
                    yield finite(Double.longBitsToDouble(bits));
                }
                default -> throw new IllegalArgumentException("Unsupported CBOR simple value");
            };
        }
        long n = argument(additional);
        return switch (major) {
            case 0 -> n;
            case 1 -> -1 - n;
            case 2 -> utf8(bytes(n));
            case 3 -> utf8(bytes(n));
            case 4 -> {
                int size = length(n);
                var list = new ArrayList<Object>(size);
                for (int i = 0; i < size; i++) {
                    list.add(read(depth + 1));
                }
                yield list;
            }
            case 5 -> {
                int size = length(n);
                var map = new LinkedHashMap<String, Object>();
                for (int i = 0; i < size; i++) {
                    Object key = read(depth + 1);
                    if (!(key instanceof Long) && !(key instanceof String)) {
                        throw new IllegalArgumentException("Unsupported CBOR map key");
                    }
                    String canonical = key.toString();
                    if (map.containsKey(canonical)) {
                        throw new IllegalArgumentException("Duplicate CBOR map key");
                    }
                    map.put(canonical, read(depth + 1));
                }
                yield map;
            }
            default -> throw new IllegalArgumentException("Unsupported CBOR tag/type");
        };
    }

    private static double half(int bits) {
        int exponent = (bits >>> 10) & 31;
        int mantissa = bits & 1023;
        double number =
                exponent == 0
                        ? Math.scalb(mantissa, -24)
                        : exponent == 31 ? Double.NaN : Math.scalb(1024 + mantissa, exponent - 25);
        return finite((bits & 32768) == 0 ? number : -number);
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite CBOR number");
        }
        return value;
    }

    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid UTF-8", error);
        }
    }
}
