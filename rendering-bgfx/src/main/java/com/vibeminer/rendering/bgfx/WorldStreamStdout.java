package com.vibeminer.rendering.bgfx;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Helper stdout, split into protocol records and unrelated output. Only a line that starts with the
 * exact {@code "WS2 "} prefix is protocol, and it is held to {@link WorldStreamStatus}'s strict
 * rules: a malformed record or a foreign nonce still ends the session. Any other line (for example
 * JVM or JFR startup text) is noise: it is never decoded, only counted and kept as a bounded,
 * redacted sample for diagnostics. Noise ends only at a newline, so nothing inside a noise line,
 * however long, can begin a record. A session that writes more than {@link #MAX_NOISE_BYTES} of
 * noise is ended as a flood.
 *
 * <p>One reader per session, used by its single status reader thread; the counters and sample may
 * be read from any thread.
 */
final class WorldStreamStdout {
    static final long MAX_NOISE_BYTES = 1L << 20;
    private static final byte[] PREFIX = "WS2 ".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_RECORD = 256;

    private final HelperStderrTail sample = new HelperStderrTail("stdout");
    private final Consumer<String> firstNoise;
    private volatile long noiseBytes, noiseLines;

    /**
     * @param firstNoise called once, on the reader thread, when the first noise line ends (or the
     *     stream ends inside it), with its redacted text; it must not throw or block for long
     */
    WorldStreamStdout(Consumer<String> firstNoise) {
        this.firstNoise = Objects.requireNonNull(firstNoise);
    }

    /** The next protocol record, skipping noise; null on EOF at a line boundary. */
    WorldStreamStatus.Message read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        byte[] line = new byte[MAX_RECORD];
        while (true) {
            int matched = 0;
            int value = -1;
            while (matched < PREFIX.length) {
                value = input.read();
                if (value == -1) {
                    if (matched > 0) {
                        noise(line, matched, true);
                    }
                    return null;
                }
                line[matched] = (byte) value;
                if (value != PREFIX[matched]) {
                    break;
                }
                matched++;
            }
            if (matched == PREFIX.length) {
                return record(input, line);
            }
            // Bytes read so far, the mismatching one included, belong to this noise line.
            if (value == '\n') {
                noise(line, matched + 1, true);
                continue;
            }
            noise(line, matched + 1, false);
            if (!skipNoise(input, line)) {
                return null;
            }
        }
    }

    /** The rest of a prefixed line, under the same rules as {@link WorldStreamStatus#read}. */
    private static WorldStreamStatus.Message record(InputStream input, byte[] line)
            throws IOException {
        for (int i = PREFIX.length; i < line.length; i++) {
            int value = input.read();
            if (value == -1) {
                throw new EOFException("Truncated World status");
            }
            line[i] = (byte) value;
            if (value == '\n') {
                return WorldStreamStatus.decode(line, i + 1);
            }
            if (value < 32 || value > 126) {
                throw new IOException("Non-ASCII World status");
            }
        }
        throw new IOException("World status exceeds line limit");
    }

    /** Consumes the rest of a noise line; false if the stream ended inside it. */
    private boolean skipNoise(InputStream input, byte[] buffer) throws IOException {
        int length = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                noise(buffer, length, true);
                return false;
            }
            buffer[length++] = (byte) value;
            if (value == '\n') {
                noise(buffer, length, true);
                return true;
            }
            if (length == buffer.length) {
                noise(buffer, length, false);
                length = 0;
            }
        }
    }

    private void noise(byte[] bytes, int length, boolean ends) throws IOException {
        long total = noiseBytes + length;
        if (total > MAX_NOISE_BYTES) {
            noiseBytes = total;
            throw new IOException(
                    "World renderer flooded stdout: more than "
                            + MAX_NOISE_BYTES
                            + " bytes of unrelated output");
        }
        noiseBytes = total;
        sample.accept(bytes, 0, length);
        if (ends) {
            if (length == 0 || bytes[length - 1] != '\n') {
                // A stream that ends inside a noise line: close it in the sample too.
                sample.accept(new byte[] {'\n'}, 0, 1);
            }
            if (++noiseLines == 1) {
                firstNoise.accept(sample.snapshot().strip());
            }
        }
    }

    long noiseBytes() {
        return noiseBytes;
    }

    long noiseLines() {
        return noiseLines;
    }

    /** Empty without noise; else a bounded, redacted diagnostic suffix for a log record. */
    String suffix() {
        long lines = noiseLines, bytes = noiseBytes;
        if (lines == 0 && bytes == 0) {
            return "";
        }
        return "; unrelated stdout ignored: "
                + lines
                + (lines == 1 ? " line, " : " lines, ")
                + bytes
                + (bytes == 1 ? " byte" : " bytes")
                + "; tail:\n"
                + sample.snapshot().stripTrailing();
    }
}
