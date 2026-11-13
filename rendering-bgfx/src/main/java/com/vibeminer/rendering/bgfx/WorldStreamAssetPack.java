package com.vibeminer.rendering.bgfx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Immutable asset pack {@code assets-<txn>.wsa} (#380 stage 4): the model and image assets an
 * objects file names by digest instead of carrying ({@link WorldStreamObjectAssets}). Bound like
 * the objects file to its session, scope, transaction, lease and tile ({@link
 * WorldStreamObjectsFile.Identity}) and to an exact length and SHA-256 ({@link
 * WorldStreamObjectsFile.Reference}).
 *
 * <p>Little-endian layout: a 256-byte header (magic, version, header size, file length, the
 * identity as the objects header holds it, the entry count and the payload bytes; every other byte
 * zero), then per entry its kind (0 model, 1 image), digest (two longs), payload length and
 * payload, padded to four bytes. Every entry's payload must hash to its digest and decode ({@link
 * WorldStreamObjectAssets#decodeModel}, {@link WorldStreamObjectAssets#decodeImage}); digests are
 * unique within a pack.
 */
final class WorldStreamAssetPack {
    private WorldStreamAssetPack() {}

    // Version 2 (#474): model assets' range classes may carry CLAMP_S and CLAMP_T.
    static final int VERSION = 2, HEADER_BYTES = 256;

    /** At most one asset per model and image an objects file can hold. */
    static final int MAX_ENTRIES =
            WorldStreamObjectsFile.MAX_MODELS + WorldStreamObjectsFile.MAX_IMAGES;

    private static final byte[] MAGIC = "VIMIWSA1".getBytes(StandardCharsets.US_ASCII);

    private static final int ENTRY_HEAD = 4 + 16 + 4;

    /**
     * A read pack: its decoded models and images by digest, and every entry's payload length (the
     * bytes the helper's asset store charges it, {@link WorldStreamAssetStore}).
     */
    record Pack(
            Map<WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ModelPart> models,
            Map<WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ImagePart> images,
            Map<WorldStreamObjectAssets.Digest, Integer> sizes) {
        static final Pack EMPTY = new Pack(Map.of(), Map.of(), Map.of());

        Pack(
                Map<WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ModelPart> models,
                Map<WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ImagePart> images) {
            this(models, images, Map.of());
        }
    }

    static String basename(long transaction) {
        return "assets-" + transaction + ".wsa";
    }

    /** The file length of a pack of {@code payloads}. */
    static long length(List<WorldStreamObjectAssets.Payload> payloads) {
        long length = HEADER_BYTES;
        for (var p : payloads) {
            length += ENTRY_HEAD + (p.bytes().length + 3L) / 4 * 4;
        }
        return length;
    }

    /** Writes and publishes one pack; the reference binds its exact length and digest. */
    static WorldStreamObjectsFile.Reference write(
            Path directory,
            WorldStreamObjectsFile.Identity identity,
            List<WorldStreamObjectAssets.Payload> payloads,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(canceled);
        require(payloads.size() <= MAX_ENTRIES, "Asset pack entries");
        long length = length(payloads);
        require(length <= WorldStreamObjectsFile.MAX_BYTES, "Asset pack length");
        var seen = new java.util.HashSet<WorldStreamObjectAssets.Digest>();
        long payloadBytes = 0;
        for (var p : payloads) {
            require(seen.add(p.digest()), "Asset pack digests repeat");
            payloadBytes += p.bytes().length;
        }
        Path target = directory.resolve(basename(identity.transaction()));
        Path part = directory.resolve(basename(identity.transaction()) + ".part");
        var digest = sha256();
        boolean created = false, published = false;
        try {
            try (var channel =
                    FileChannel.open(
                            part,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                var output =
                        new DataOutputStream(
                                new DigestOutputStream(
                                        new BufferedOutputStream(
                                                Channels.newOutputStream(channel), 64 << 10),
                                        digest));
                output.write(header(identity, length, payloads.size(), payloadBytes));
                for (var p : payloads) {
                    check(canceled);
                    output.writeInt(Integer.reverseBytes(p.kind().ordinal()));
                    output.writeLong(Long.reverseBytes(p.digest().high()));
                    output.writeLong(Long.reverseBytes(p.digest().low()));
                    output.writeInt(Integer.reverseBytes(p.bytes().length));
                    output.write(p.bytes());
                    for (int pad = p.bytes().length; pad % 4 != 0; pad++) {
                        output.writeByte(0);
                    }
                }
                output.flush();
                if (channel.size() != length) {
                    throw new IOException("Asset pack length differs");
                }
            }
            check(canceled);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Asset pack exists");
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new WorldStreamObjectsFile.Reference(
                    Math.toIntExact(length), HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (created && !published) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** Reads and fully validates one pack bound to exactly this identity and reference. */
    static Pack read(
            Path path,
            WorldStreamObjectsFile.Identity identity,
            WorldStreamObjectsFile.Reference reference,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(canceled);
        if (path.getFileName() == null
                || !path.getFileName().toString().equals(basename(identity.transaction()))) {
            throw new IOException("Unexpected asset pack basename");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset pack is not a regular private file");
        }
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != reference.length()) {
                throw new IOException("Asset pack size");
            }
            var digest = sha256();
            var input =
                    new DataInputStream(
                            new DigestInputStream(
                                    new BufferedInputStream(
                                            Channels.newInputStream(channel), 64 << 10),
                                    digest));
            byte[] head = new byte[HEADER_BYTES];
            input.readFully(head);
            var expected =
                    header(
                            identity,
                            reference.length(),
                            ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(112),
                            ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(116));
            require(Arrays.equals(expected, head), "Asset pack header");
            var in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int entries = in.getInt(112);
            long payloadBytes = in.getLong(116);
            require(
                    entries >= 0 && entries <= MAX_ENTRIES && payloadBytes >= 0,
                    "Asset pack counts");
            var models =
                    new LinkedHashMap<
                            WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ModelPart>();
            var images =
                    new LinkedHashMap<
                            WorldStreamObjectAssets.Digest, WorldStreamObjectAssets.ImagePart>();
            var sizes = new LinkedHashMap<WorldStreamObjectAssets.Digest, Integer>();
            long left = reference.length() - HEADER_BYTES, payloadLeft = payloadBytes;
            for (int e = 0; e < entries; e++) {
                check(canceled);
                require(left >= ENTRY_HEAD, "Asset pack entry bounds");
                int kind = Integer.reverseBytes(input.readInt());
                var named =
                        new WorldStreamObjectAssets.Digest(
                                Long.reverseBytes(input.readLong()),
                                Long.reverseBytes(input.readLong()));
                int size = Integer.reverseBytes(input.readInt());
                long padded = (size + 3L) / 4 * 4;
                require(
                        kind >= 0
                                && kind < WorldStreamObjectAssets.Kind.values().length
                                && size >= 0
                                && size <= payloadLeft
                                && ENTRY_HEAD + padded <= left,
                        "Asset pack entry bounds");
                left -= ENTRY_HEAD + padded;
                payloadLeft -= size;
                byte[] payload = new byte[size];
                input.readFully(payload);
                for (long pad = size; pad < padded; pad++) {
                    require(input.readByte() == 0, "Asset pack padding");
                }
                require(
                        WorldStreamObjectAssets.digestOf(payload).equals(named),
                        "Asset pack digest");
                require(
                        !models.containsKey(named) && !images.containsKey(named),
                        "Asset pack digests repeat");
                sizes.put(named, size);
                if (kind == WorldStreamObjectAssets.Kind.MODEL.ordinal()) {
                    models.put(named, WorldStreamObjectAssets.decodeModel(payload));
                } else {
                    images.put(named, WorldStreamObjectAssets.decodeImage(payload));
                }
            }
            require(left == 0 && payloadLeft == 0, "Asset pack length");
            if (input.read() != -1
                    || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256())) {
                throw new IOException("Asset pack bytes or digest differ");
            }
            return new Pack(Map.copyOf(models), Map.copyOf(images), Map.copyOf(sizes));
        } catch (EOFException truncated) {
            throw new IOException("Truncated asset pack", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid asset pack", invalid);
        }
    }

    private static byte[] header(
            WorldStreamObjectsFile.Identity identity, long length, int entries, long payloadBytes) {
        var out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(HEADER_BYTES).putLong(length);
        out.putLong(identity.nonce().high()).putLong(identity.nonce().low());
        out.put(HexFormat.of().parseHex(identity.scope())).putLong(identity.transaction());
        out.putLong(identity.lease().generation()).putInt(identity.lease().slot());
        out.putInt(identity.x()).putInt(identity.y()).putInt(0).putLong(identity.lease().serial());
        out.putInt(entries).putLong(payloadBytes);
        return out.array();
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("Asset pack canceled");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
