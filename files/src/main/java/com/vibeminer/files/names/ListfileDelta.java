package com.vibeminer.files.names;

import static com.vibeminer.files.names.ListfileDeltaException.Problem.BROKEN_CHAIN;
import static com.vibeminer.files.names.ListfileDeltaException.Problem.INPUT_MISMATCH;
import static com.vibeminer.files.names.ListfileDeltaException.Problem.MALFORMED;
import static com.vibeminer.files.names.ListfileDeltaException.Problem.WRONG_TARGET;

import com.vibeminer.access.Cancellation;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Portable VMLD1 codec. CSV units and replay stay bounded to 64KiB per chain link. */
public final class ListfileDelta {
    public static final int MAX_UNIT_BYTES = 64 << 10, MAX_CHAIN_DELTAS = 16;

    public record Header(String baseSha256, String targetSha256, long targetSize) {}

    private record Op(long id, byte[] bytes) {}

    private record Parsed(Header header, byte[] lead, List<Op> ops) {}

    private record Unit(long id, byte[] bytes) {}

    private ListfileDelta() {}

    public static Header readHeader(byte[] delta) {
        return parse(delta).header;
    }

    public static byte[] encode(
            InputStream base,
            InputStream target,
            String baseSha,
            String targetSha,
            long targetSize,
            int maxBytes,
            Cancellation c) {
        if (targetSize < 0 || maxBytes < 88 || !validSha(baseSha) || !validSha(targetSha)) {
            throw new IllegalArgumentException("Invalid delta bounds/hash");
        }
        try {
            Units b = new Units(base, c), t = new Units(target, c);
            Unit bu = b.next(), tu = t.next();
            byte[] lead = t.lead;
            int fixed = 88 + lead.length;
            if (fixed > maxBytes) {
                return null;
            }
            ByteArrayOutputStream ops = new ByteArrayOutputStream();
            long previous = 0;
            int count = 0;
            while (bu != null || tu != null) {
                c.check();
                if (tu == null || bu != null && bu.id < tu.id) {
                    ops.write(2);
                    ListfileStore.writeVar(ops, bu.id - previous);
                    previous = bu.id;
                    count++;
                    bu = b.next();
                } else if (bu == null || tu.id < bu.id) {
                    ops.write(1);
                    ListfileStore.writeVar(ops, tu.id - previous);
                    ListfileStore.writeVar(ops, tu.bytes.length);
                    ops.writeBytes(tu.bytes);
                    previous = tu.id;
                    count++;
                    tu = t.next();
                } else {
                    if (!Arrays.equals(bu.bytes, tu.bytes)) {
                        ops.write(1);
                        ListfileStore.writeVar(ops, tu.id - previous);
                        ListfileStore.writeVar(ops, tu.bytes.length);
                        ops.writeBytes(tu.bytes);
                        previous = tu.id;
                        count++;
                    }
                    bu = b.next();
                    tu = t.next();
                }
                if ((long) fixed + ops.size() > maxBytes) {
                    return null;
                }
            }
            if (!b.sha().equals(baseSha) || !t.sha().equals(targetSha) || t.length != targetSize) {
                throw error(INPUT_MISMATCH, "Input size/hash differs");
            }
            ByteBuffer out = ByteBuffer.allocate(fixed + ops.size()).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x444c4d56)
                    .putShort((short) 1)
                    .putShort((short) 0)
                    .put(HexFormat.of().parseHex(baseSha))
                    .put(HexFormat.of().parseHex(targetSha))
                    .putLong(targetSize)
                    .putInt(lead.length)
                    .put(lead)
                    .putInt(count)
                    .put(ops.toByteArray());
            return out.array();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void apply(
            InputStream base, List<byte[]> deltas, OutputStream output, Cancellation c) {
        if (deltas.size() > MAX_CHAIN_DELTAS) {
            throw error(MALFORMED, "Delta chain too long");
        }
        List<Parsed> parsed = deltas.stream().map(ListfileDelta::parse).toList();
        for (int i = 1; i < parsed.size(); i++) {
            if (!parsed.get(i - 1).header.targetSha256.equals(parsed.get(i).header.baseSha256)) {
                throw error(BROKEN_CHAIN, "Hashes do not link");
            }
        }
        InputStream input = base;
        for (Parsed p : parsed) {
            input = new Replay(input, p, c);
        }
        byte[] buffer = new byte[64 << 10];
        try {
            int n;
            while ((n = input.read(buffer)) != -1) {
                c.check();
                output.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static InputStream replay(InputStream base, List<byte[]> deltas, Cancellation c) {
        if (deltas.size() > MAX_CHAIN_DELTAS) {
            throw error(MALFORMED, "Delta chain too long");
        }
        String previous = null;
        InputStream input = base;
        for (byte[] bytes : deltas) {
            Parsed p = parse(bytes);
            if (previous != null && !previous.equals(p.header.baseSha256)) {
                throw error(BROKEN_CHAIN, "Hashes do not link");
            }
            previous = p.header.targetSha256;
            input = new Replay(input, p, c);
        }
        return input;
    }

    private static Parsed parse(byte[] bytes) {
        try {
            if (bytes.length < 88) {
                throw error(MALFORMED, "Short delta");
            }
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != 0x444c4d56 || b.getShort() != 1 || b.getShort() != 0) {
                throw error(MALFORMED, "Unknown magic/version/flags");
            }
            byte[] base = new byte[32], target = new byte[32];
            b.get(base).get(target);
            long size = b.getLong();
            int n = b.getInt();
            if (size < 0 || n < 0 || n > MAX_UNIT_BYTES || n > size || n > b.remaining() - 4) {
                throw error(MALFORMED, "Invalid lead/size");
            }
            byte[] lead = new byte[n];
            b.get(lead);
            long count = Integer.toUnsignedLong(b.getInt());
            if (count > b.remaining() / 2) {
                throw error(MALFORMED, "Impossible op count");
            }
            List<Op> ops = new ArrayList<>();
            long previous = 0;
            for (long i = 0; i < count; i++) {
                int kind = b.get() & 255;
                long delta = var(b), id = previous + delta;
                if (id > 0xffff_ffffL || i > 0 && delta == 0) {
                    throw error(MALFORMED, "Op IDs not ascending");
                }
                previous = id;
                byte[] unit = null;
                if (kind == 1) {
                    long length = var(b);
                    if (length < 1 || length > MAX_UNIT_BYTES || length > b.remaining()) {
                        throw error(MALFORMED, "Invalid Set length");
                    }
                    unit = new byte[(int) length];
                    b.get(unit);
                    Units source = new Units(new ByteArrayInputStream(unit), Cancellation.NONE);
                    Unit first = source.next();
                    if (source.lead.length != 0
                            || first == null
                            || first.id != id
                            || source.next() != null) {
                        throw error(MALFORMED, "Set does not contain exactly its ID unit");
                    }
                } else if (kind != 2) {
                    throw error(MALFORMED, "Unknown operation");
                }
                ops.add(new Op(id, unit));
            }
            if (b.hasRemaining()) {
                throw error(MALFORMED, "Trailing delta bytes");
            }
            return new Parsed(
                    new Header(
                            HexFormat.of().formatHex(base), HexFormat.of().formatHex(target), size),
                    lead,
                    List.copyOf(ops));
        } catch (BufferUnderflowException | ListfileFormatException | IOException e) {
            throw error(MALFORMED, "Truncated or invalid delta");
        }
    }

    private static long var(ByteBuffer b) {
        long value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!b.hasRemaining()) {
                throw error(MALFORMED, "Truncated varint");
            }
            int x = b.get() & 255;
            if (shift == 28 && x > 15) {
                throw error(MALFORMED, "Oversized varint");
            }
            value |= (long) (x & 127) << shift;
            if (x < 128) {
                return value;
            }
        }
        throw error(MALFORMED, "Oversized varint");
    }

    private static boolean validSha(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }

    private static ListfileDeltaException error(ListfileDeltaException.Problem p, String message) {
        return new ListfileDeltaException(p, message);
    }

    private static final class Units {
        final InputStream input;
        final Cancellation cancellation;
        final MessageDigest hash = ListfileStore.digest();
        byte[] lead = new byte[0], pending;
        long previous = -1, length;
        boolean started, eof;

        Units(InputStream input, Cancellation c) {
            this.input =
                    input instanceof BufferedInputStream
                            ? input
                            : new BufferedInputStream(input, 64 << 10);
            this.cancellation = c;
        }

        Unit next() throws IOException {
            if (eof && pending == null) {
                return null;
            }
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            long id = -1;
            if (pending != null) {
                id = id(pending);
                data.writeBytes(pending);
                pending = null;
            }
            while (true) {
                byte[] line = line();
                if (line == null) {
                    eof = true;
                    break;
                }
                Long next = id(line);
                if (next != null) {
                    if (next <= previous) {
                        throw new ListfileFormatException("CSV IDs do not ascend");
                    }
                    previous = next;
                    if (id >= 0) {
                        pending = line;
                        break;
                    }
                    if (!started) {
                        lead = data.toByteArray();
                        data.reset();
                        started = true;
                    }
                    id = next;
                }
                data.writeBytes(line);
                if (data.size() > MAX_UNIT_BYTES) {
                    throw new ListfileFormatException("CSV unit/lead exceeds 64KiB");
                }
            }
            if (id < 0) {
                if (!started) {
                    lead = data.toByteArray();
                    started = true;
                }
                return null;
            }
            return new Unit(id, data.toByteArray());
        }

        byte[] line() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (; ; ) {
                cancellation.check();
                int b = input.read();
                if (b < 0) {
                    return out.size() == 0 ? null : out.toByteArray();
                }
                hash.update((byte) b);
                length++;
                out.write(b);
                if (out.size() > MAX_UNIT_BYTES) {
                    throw new ListfileFormatException("CSV line exceeds64KiB");
                }
                if (b == 10) {
                    return out.toByteArray();
                }
            }
        }

        String sha() {
            if (!eof) {
                throw new IllegalStateException("Input not exhausted");
            }
            return HexFormat.of().formatHex(hash.digest());
        }

        static Long id(byte[] line) {
            int i = 0;
            while (i < line.length && line[i] >= '0' && line[i] <= '9') {
                i++;
            }
            if (i == 0 || i >= line.length || line[i] != ';') {
                return null;
            }
            if (i > 10 || i > 1 && line[0] == '0') {
                throw new ListfileFormatException("Noncanonical ID");
            }
            long value = 0;
            for (int j = 0; j < i; j++) {
                value = value * 10 + line[j] - '0';
            }
            if (value > 0xffff_ffffL) {
                throw new ListfileFormatException("Oversized ID");
            }
            return value;
        }
    }

    private static final class Replay extends InputStream {
        final Units base;
        final Parsed delta;
        final Cancellation cancellation;
        final MessageDigest target = ListfileStore.digest();
        Unit unit;
        int op, position;
        byte[] pending;
        long written;
        boolean initialized, done;
        String problem;

        Replay(InputStream base, Parsed delta, Cancellation c) {
            this.base = new Units(new BufferedInputStream(base, 64 << 10), c);
            this.delta = delta;
            this.cancellation = c;
            pending = delta.lead;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, bytes.length);
            if (len == 0) {
                return 0;
            }
            cancellation.check();
            while (position == pending.length) {
                if (done) {
                    return -1;
                }
                pending = next();
                position = 0;
                if (pending == null) {
                    done = true;
                    pending = new byte[0];
                    return -1;
                }
            }
            int n = Math.min(len, pending.length - position);
            System.arraycopy(pending, position, bytes, off, n);
            target.update(pending, position, n);
            position += n;
            written += n;
            return n;
        }

        byte[] next() throws IOException {
            try {
                if (!initialized) {
                    unit = base.next();
                    initialized = true;
                }
                while (unit != null || op < delta.ops.size()) {
                    Op change = op < delta.ops.size() ? delta.ops.get(op) : null;
                    if (change == null || unit != null && unit.id < change.id) {
                        byte[] result = unit.bytes;
                        unit = base.next();
                        return result;
                    }
                    if (unit == null || change.id < unit.id) {
                        op++;
                        if (change.bytes == null) {
                            problem = "Remove names absent ID";
                            continue;
                        }
                        return change.bytes;
                    }
                    op++;
                    unit = base.next();
                    if (change.bytes != null) {
                        return change.bytes;
                    }
                }
            } catch (ListfileFormatException e) {
                throw error(BROKEN_CHAIN, "Base CSV does not split");
            }
            String actual = base.sha();
            if (!actual.equals(delta.header.baseSha256)) {
                throw error(BROKEN_CHAIN, "Base hash differs");
            }
            if (problem != null) {
                throw error(MALFORMED, problem);
            }
            if (written != delta.header.targetSize
                    || !HexFormat.of()
                            .formatHex(target.digest())
                            .equals(delta.header.targetSha256)) {
                throw error(WRONG_TARGET, "Target hash or size differs");
            }
            return null;
        }
    }
}
