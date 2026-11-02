package com.vibeminer.files.names;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Incremental strict, sorted wow-listfile CSV reader. Keeps at most one line. */
public final class ListfileParser {
    public static final int MAX_NAME_BYTES = 1024,
            MAX_LINE_BYTES = 1036,
            MAX_NAMES = 8_000_000,
            MAX_BLOB_BYTES = 512 << 20;

    @FunctionalInterface
    public interface Sink {
        void accept(long id, byte[] utf8Name);
    }

    private final Sink sink;
    private final int maxNames, maxBlob;
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream();
    private long previous = -1, line, blob, count;
    private boolean failed, finished;

    public ListfileParser(Sink sink) {
        this(sink, MAX_NAMES, MAX_BLOB_BYTES);
    }

    public ListfileParser(Sink sink, int maxNames, int maxBlob) {
        this.sink = Objects.requireNonNull(sink);
        if (maxNames < 1 || maxNames > MAX_NAMES || maxBlob < 1 || maxBlob > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("Invalid CSV caps");
        }
        this.maxNames = maxNames;
        this.maxBlob = maxBlob;
    }

    public long count() {
        return count;
    }

    public void feed(byte[] bytes) {
        feed(bytes, 0, bytes.length);
    }

    public void feed(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        usable();
        if (finished) {
            throw new IllegalStateException("Parser complete");
        }
        try {
            for (int i = offset; i < offset + length; i++) {
                int b = bytes[i] & 255;
                if (b == 10) {
                    parse(carry.toByteArray());
                    carry.reset();
                } else {
                    if (carry.size() == MAX_LINE_BYTES) {
                        throw invalid("Line too long");
                    }
                    carry.write(b);
                }
            }
        } catch (ListfileFormatException e) {
            failed = true;
            throw e;
        }
    }

    public long finish() {
        usable();
        if (finished) {
            return count;
        }
        try {
            if (carry.size() > 0) {
                parse(carry.toByteArray());
                carry.reset();
            }
            if (count == 0) {
                throw invalid("No names");
            }
            finished = true;
            return count;
        } catch (ListfileFormatException e) {
            failed = true;
            throw e;
        }
    }

    private void usable() {
        if (failed) {
            throw new IllegalStateException("Parser refused input");
        }
    }

    private ListfileFormatException invalid(String detail) {
        return new ListfileFormatException("Line " + line + ": " + detail);
    }

    private void parse(byte[] bytes) {
        line++;
        int end = bytes.length;
        if (end > 0 && bytes[end - 1] == 13) {
            end--;
        }
        if (end == 0) {
            return;
        }
        int sep = 0;
        while (sep < end && bytes[sep] != ';') {
            sep++;
        }
        if (sep < 1 || sep > 10 || sep == end || sep > 1 && bytes[0] == '0') {
            throw invalid("Noncanonical FileDataID");
        }
        long id = 0;
        for (int i = 0; i < sep; i++) {
            if (bytes[i] < '0' || bytes[i] > '9') {
                throw invalid("Invalid FileDataID");
            }
            id = id * 10 + bytes[i] - '0';
        }
        if (id > 0xffff_ffffL || id <= previous) {
            throw invalid("FileDataIDs must ascend within uint32");
        }
        int n = end - sep - 1;
        if (n < 1 || n > MAX_NAME_BYTES) {
            throw invalid("Name length");
        }
        String name;
        try {
            name =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, sep + 1, n))
                            .toString();
        } catch (CharacterCodingException e) {
            throw invalid("Invalid UTF-8");
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            if (c < 32 || c >= 127 && c <= 159) {
                throw invalid("Control character");
            }
            if (c == 0x61c
                    || c == 0x200e
                    || c == 0x200f
                    || c >= 0x202a && c <= 0x202e
                    || c >= 0x2066 && c <= 0x2069) {
                continue;
            }
            kept.appendCodePoint(c);
        }
        byte[] clean = kept.toString().getBytes(StandardCharsets.UTF_8);
        if (clean.length == 0) {
            throw invalid("Only direction marks");
        }
        if (count == maxNames || blob + clean.length > maxBlob) {
            throw invalid("Names exceed limits");
        }
        blob += clean.length;
        previous = id;
        count++;
        sink.accept(id, clean);
    }
}
