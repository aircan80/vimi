package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Descriptive payload identity only; never source authority or a composition-cache receipt. */
final class WorldStreamMaterialDigest {
    private static final String DOMAIN = "VIMI_WORLD_STREAM_MATERIAL_V1";
    private static final int SCRATCH_BYTES = 4096;

    private WorldStreamMaterialDigest() {}

    /**
     * Synchronous: caller pins the exact immutable qualified atlas through return. Ignores the
     * template's existing digest and transport counters. Source final validation remains external.
     */
    static String compute(
            WorldStreamTileFile.Header header,
            ByteBuffer rgba,
            String profile,
            BooleanSupplier canceled) {
        Objects.requireNonNull(header);
        Objects.requireNonNull(rgba);
        Objects.requireNonNull(canceled);
        if (profile == null || profile.isEmpty() || profile.length() > 128) {
            throw new IllegalArgumentException("Material profile bounds");
        }
        for (int i = 0; i < profile.length(); i++) {
            if (profile.charAt(i) < 0x21 || profile.charAt(i) > 0x7e) {
                throw new IllegalArgumentException("Material profile must be printable ASCII");
            }
        }
        if (header.outcome() != WorldStreamTileFile.Outcome.TEXTURED
                || !rgba.isReadOnly()
                || rgba.remaining() != WorldStreamTileFile.ATLAS_BYTES) {
            throw new IllegalArgumentException("Material identity requires one readonly atlas");
        }
        check(canceled);
        var hash = sha256();
        text(hash, DOMAIN);
        text(hash, profile);
        text(hash, header.scope());
        number(hash, header.x());
        number(hash, header.y());
        number(hash, header.chunks());
        number(hash, header.vertices());
        number(hash, header.indices());
        text(hash, header.geometrySha256());
        // The private wire recipe remains one. A future recipe needs an explicit profile review.
        number(hash, 1);
        for (long word : header.coverage()) {
            number(hash, word);
        }
        number(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(header.maxX())));
        number(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(header.maxY())));
        number(hash, Double.doubleToRawLongBits(header.uvError()));
        source(hash, header.wdt());
        source(hash, header.root());
        source(hash, header.tex0());
        number(hash, header.diffuse().size());
        for (var source : header.diffuse()) {
            check(canceled);
            source(hash, source);
        }
        number(hash, rgba.remaining());
        var input = rgba.asReadOnlyBuffer();
        byte[] scratch = new byte[SCRATCH_BYTES];
        while (input.hasRemaining()) {
            check(canceled);
            int count = Math.min(input.remaining(), scratch.length);
            input.get(scratch, 0, count);
            hash.update(scratch, 0, count);
        }
        check(canceled);
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void source(MessageDigest hash, WorldStreamTileFile.Source source) {
        number(hash, source.id());
        text(hash, source.contentKey());
        number(hash, source.consumedLength());
        number(hash, source.declaredLength() == null ? -1 : source.declaredLength());
        number(hash, source.sourceLimit());
        number(hash, source.originalSide());
        number(hash, source.mipLevel());
        number(hash, source.mipFlags());
        number(hash, source.encoding() == null ? -1 : source.encoding().ordinal());
        number(hash, source.alphaDepth());
        number(hash, source.alphaEncoding());
    }

    private static void text(MessageDigest hash, String value) {
        number(hash, value.length());
        hash.update(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void number(MessageDigest hash, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            hash.update((byte) (value >>> (i * 8)));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
