package com.vibeminer.builds.formats;

import com.vibeminer.builds.BuildFailure;
import com.vibeminer.builds.BuildFormatException;
import com.vibeminer.network.CdnKey;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Authenticated whole BLTE metadata decoder; encrypted and unsupported modes stay distinct. */
public final class Blte {
    public static final long MAX_ENCODED_BYTES = 512L << 20, MAX_DECODED_BYTES = 1L << 30;
    public static final int MAX_CHUNKS = 65536, MAX_COMPRESSED_CHUNK_BYTES = 16 << 20;

    private record Chunk(int at, int encoded, Long decoded, byte[] hash) {}

    private Blte() {}

    public static byte[] decode(
            byte[] encoded,
            CdnKey eKey,
            CdnKey cKey,
            Long decodedLength,
            long maxDecodedBytes,
            Runnable cancellation) {
        if (maxDecodedBytes < 0 || maxDecodedBytes > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException("Invalid BLTE decoded bound");
        }
        cancellation.run();
        if (encoded.length > MAX_ENCODED_BYTES
                || decodedLength != null && decodedLength > maxDecodedBytes) {
            throw limit("BLTE exceeds object byte bounds");
        }
        if (encoded.length < 9 || decodedLength != null && decodedLength < 0) {
            throw invalid("Invalid declared BLTE lengths");
        }
        if (encoded[0] != 'B' || encoded[1] != 'L' || encoded[2] != 'T' || encoded[3] != 'E') {
            throw invalid("Missing BLTE magic");
        }
        long header = FormatSupport.u32(encoded, 4);
        boolean headerless = header == 0;
        List<Chunk> chunks = new ArrayList<>();
        if (headerless) {
            chunks.add(new Chunk(8, encoded.length - 8, decodedLength, null));
        } else {
            if (header > 12L + 24L * MAX_CHUNKS) {
                throw limit("BLTE header exceeds chunk bound");
            }
            if (header < 12 || header > encoded.length) {
                throw invalid("Invalid BLTE header length");
            }
            verify(
                    FormatSupport.md5(encoded, 0, (int) header),
                    eKey.bytes(),
                    "Encoding header key differs");
            if (encoded[8] != 15) {
                throw unsupported("Unsupported BLTE chunk table");
            }
            int count =
                    (Byte.toUnsignedInt(encoded[9]) << 16)
                            | (Byte.toUnsignedInt(encoded[10]) << 8)
                            | Byte.toUnsignedInt(encoded[11]);
            if (count > MAX_CHUNKS) {
                throw limit("BLTE chunk count exceeds bound");
            }
            if (count == 0 || header != 12L + 24L * count) {
                throw invalid("BLTE chunk table size differs");
            }
            long at = header, totalDecoded = 0;
            for (int i = 0; i < count; i++) {
                cancellation.run();
                int row = 12 + i * 24;
                long input = FormatSupport.u32(encoded, row),
                        output = FormatSupport.u32(encoded, row + 4);
                if (input == 0) {
                    throw invalid("BLTE chunk has no mode byte");
                }
                long next = at + input;
                totalDecoded += output;
                if (next > MAX_ENCODED_BYTES || totalDecoded > maxDecodedBytes) {
                    throw limit("BLTE chunk totals exceed bounds");
                }
                if (next > encoded.length) {
                    throw invalid("BLTE chunk totals exceed input");
                }
                chunks.add(
                        new Chunk(
                                (int) at,
                                (int) input,
                                output,
                                FormatSupport.slice(encoded, row + 8, 16)));
                at = next;
            }
            if (at != encoded.length || decodedLength != null && totalDecoded != decodedLength) {
                throw invalid("BLTE chunk totals differ from declared lengths");
            }
        }
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        (int) Math.min(decodedLength == null ? 0 : decodedLength, encoded.length));
        byte[] buffer = new byte[65536];
        for (Chunk chunk : chunks) {
            cancellation.run();
            int mode = Byte.toUnsignedInt(encoded[chunk.at()]);
            int remaining = chunk.encoded() - 1, at = chunk.at() + 1;
            if (mode == 'N') {
                if (chunk.decoded() != null && chunk.decoded() != remaining) {
                    throw invalid("Raw BLTE chunk has wrong decoded length");
                }
                if ((long) output.size() + remaining > maxDecodedBytes) {
                    throw limit("BLTE exceeds decoded bound");
                }
                for (int i = 0; i < remaining; i += 65536) {
                    cancellation.run();
                    output.write(encoded, at + i, Math.min(65536, remaining - i));
                }
            } else if (mode == 'Z') {
                if (remaining > MAX_COMPRESSED_CHUNK_BYTES) {
                    throw limit("Compressed chunk exceeds working-memory bound");
                }
                Inflater inflater = new Inflater();
                inflater.setInput(encoded, at, remaining);
                long produced = 0,
                        cap =
                                chunk.decoded() == null
                                        ? maxDecodedBytes - output.size()
                                        : chunk.decoded();
                try {
                    while (!inflater.finished()) {
                        cancellation.run();
                        int take = (int) Math.min(buffer.length, cap - produced + 1);
                        int n = inflater.inflate(buffer, 0, take);
                        if (n > cap - produced) {
                            throw chunk.decoded() == null
                                    ? limit("Inflated bytes exceed bound")
                                    : invalid("Inflated chunk exceeds declared length");
                        }
                        if ((long) output.size() + n > maxDecodedBytes) {
                            throw limit("BLTE exceeds decoded bound");
                        }
                        output.write(buffer, 0, n);
                        produced += n;
                        if (n == 0 && !inflater.finished()) {
                            throw invalid("Zlib member is truncated or needs a dictionary");
                        }
                    }
                    if (inflater.getRemaining() != 0
                            || chunk.decoded() != null && produced != chunk.decoded()) {
                        throw invalid("Zlib has trailing bytes or wrong decoded length");
                    }
                } catch (DataFormatException e) {
                    throw invalid("Invalid zlib member");
                } finally {
                    inflater.end();
                }
            } else {
                verify(
                        FormatSupport.md5(
                                encoded,
                                headerless ? 0 : chunk.at(),
                                headerless ? encoded.length : chunk.encoded()),
                        headerless ? eKey.bytes() : chunk.hash(),
                        "Unsupported chunk failed authentication");
                if (mode == 'E') {
                    throw new BuildFormatException(
                            BuildFailure.ENCRYPTED,
                            "BLTE_ENCRYPTED",
                            "BLTE has encrypted metadata");
                }
                throw unsupported("Unsupported BLTE mode");
            }
            if (!headerless) {
                verify(
                        FormatSupport.md5(encoded, chunk.at(), chunk.encoded()),
                        chunk.hash(),
                        "BLTE chunk checksum differs");
            }
        }
        cancellation.run();
        if (headerless) {
            verify(FormatSupport.md5(encoded), eKey.bytes(), "BLTE encoding key differs");
        }
        byte[] result = output.toByteArray();
        if (cKey != null) {
            verify(FormatSupport.md5(result), cKey.bytes(), "BLTE content key differs");
        }
        return result;
    }

    private static void verify(byte[] actual, byte[] expected, String message) {
        FormatSupport.hash(actual, expected, "BLTE_INTEGRITY", message);
    }

    private static BuildFormatException invalid(String message) {
        return FormatSupport.corrupt("BLTE_INVALID", message);
    }

    private static BuildFormatException limit(String message) {
        return FormatSupport.corrupt("BLTE_LIMIT", message);
    }

    private static BuildFormatException unsupported(String message) {
        return FormatSupport.unsupported("BLTE_UNSUPPORTED", message);
    }
}
