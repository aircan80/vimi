package com.vibeminer.store;

import java.nio.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import com.vibeminer.store.StoreTypes.PutObject;

public final class History {
    private History() { }
    public record Observation(int kind, String product, String region, Instant observedAt, String url, byte[] reply) {
        public Observation { reply = reply.clone(); }
        @Override public byte[] reply() { return reply.clone(); }
    }
    public record Entry(int kind, String product, String region, Instant observedAt, String url, ObjectKey document, ObjectKey record) { }
    public record Query(String product, String region, Integer kind, Entry after, int limit) {
        public Query { if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("History page limit is 1..10000"); }
        public Query() { this(null, null, null, null, 1000); }
    }
    public static List<PutObject> objects(Observation observation) {
        byte[] reply = observation.reply();
        if (reply.length == 0) throw new IllegalArgumentException("Reply must not be empty");
        ObjectKey document = new ObjectKey(ObjectKey.HISTORY_DOCUMENT, Format.hash("MD5", reply));
        byte[] record = encode(observation.kind(), observation.product(), observation.region(), observation.observedAt(), observation.url(), document);
        return List.of(new PutObject(document, reply), new PutObject(new ObjectKey(ObjectKey.HISTORY_RECORD, Format.hash("MD5", record)), record));
    }
    public static byte[] encode(int kind, String product, String region, Instant at, String url, ObjectKey document) {
        validate(kind, product, region);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(url == null ? "" : url));
            byte[] u = new byte[encoded.remaining()]; encoded.get(u);
            if (u.length > 1024) throw new IllegalArgumentException("URL exceeds 1024 UTF-8 bytes");
            return ByteBuffer.allocate(32 + product.length() + region.length() + u.length).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte)1).put((byte)kind).putShort((short)0).putLong(at.toEpochMilli()).put(document.bytes())
                .put((byte)product.length()).put(product.getBytes(StandardCharsets.US_ASCII))
                .put((byte)region.length()).put(region.getBytes(StandardCharsets.US_ASCII)).putShort((short)u.length).put(u).array();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("URL contains unpaired surrogate", e); }
    }
    private static void validate(int kind, String product, String region) {
        if (kind < 1 || kind > 8 || product == null || (kind == 4 ? !product.isEmpty() : !product.matches("[a-z0-9_-]{1,64}"))
            || region == null || !region.matches("[a-z0-9]{1,16}")) throw new IllegalArgumentException("Invalid history fields");
    }
    static Entry parse(byte[] bytes, ObjectKey record) {
        try {
            if (bytes.length < 33 || bytes.length > 1136) return null;
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.get() != 1) return null;
            int kind = Byte.toUnsignedInt(b.get()); if (b.getShort() != 0) return null;
            Instant time = Instant.ofEpochMilli(b.getLong()); byte[] key = new byte[16]; b.get(key);
            byte[] p = new byte[Byte.toUnsignedInt(b.get())]; b.get(p);
            byte[] r = new byte[Byte.toUnsignedInt(b.get())]; b.get(r);
            // Reject non-ASCII instead of allowing decoder replacement.
            for (byte value : p) if (value < 0) return null;
            for (byte value : r) if (value < 0) return null;
            String product = new String(p, StandardCharsets.US_ASCII), region = new String(r, StandardCharsets.US_ASCII);
            validate(kind, product, region);
            int u = Short.toUnsignedInt(b.getShort()); if (u > 1024 || b.remaining() != u) return null;
            String url = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(b).toString();
            return new Entry(kind, product, region, time, url, new ObjectKey(ObjectKey.HISTORY_DOCUMENT, key), record);
        } catch (IllegalArgumentException | BufferUnderflowException | CharacterCodingException e) { return null; }
    }
}
