package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.PreviewMeshCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * One bounded immutable geometry snapshot for a renderer child; never application/library access.
 */
public final class GpuSceneFile {
    private static final long MAGIC = 0x56494d4947505531L;
    private static final int HEADER_BYTES = 56;
    public static final int MAX_BYTES = MeshAppearanceCodec.MAX_BYTES + 2048;

    public record Payload(PreviewMesh mesh, MeshAppearance appearance, Set<Integer> visibleParts) {
        public Payload {
            java.util.Objects.requireNonNull(mesh);
            visibleParts = Set.copyOf(visibleParts);
            if (visibleParts.size() > PreviewMesh.MAX_PARTS
                    || appearance != null && appearance.mesh() != mesh) {
                throw new IllegalArgumentException("Invalid GPU preview snapshot");
            }
        }
    }

    private GpuSceneFile() {}

    public static void write(
            Path destination,
            PreviewMesh mesh,
            MeshAppearance appearance,
            Set<Integer> visibleParts)
            throws IOException {
        var payload = new Payload(mesh, appearance, visibleParts);
        byte[] encoded =
                appearance == null
                        ? PreviewMeshCodec.encode(payload.mesh())
                        : MeshAppearanceCodec.encode(appearance);
        if (encoded.length > MeshAppearanceCodec.MAX_BYTES) {
            throw new IOException("GPU preview snapshot exceeds payload bound");
        }
        try (var output =
                new DataOutputStream(
                        Files.newOutputStream(
                                destination,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE))) {
            output.writeLong(MAGIC);
            output.writeInt(appearance == null ? 0 : 1);
            output.writeInt(payload.visibleParts().size());
            output.writeInt(encoded.length);
            output.writeInt(0);
            output.write(digest(encoded, appearance == null ? 0 : 1, payload.visibleParts()));
            for (int part : payload.visibleParts().stream().sorted().toList()) {
                output.writeInt(part);
            }
            output.write(encoded);
        }
    }

    public static Payload read(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GPU preview snapshot must be a regular file");
        }
        long size = Files.size(source);
        if (size < HEADER_BYTES || size > MAX_BYTES) {
            throw new IOException("GPU preview snapshot length exceeds bounds");
        }
        try (var input = new DataInputStream(Files.newInputStream(source))) {
            long magic = input.readLong();
            int flags = input.readInt(), count = input.readInt(), length = input.readInt();
            int reserved = input.readInt();
            if (magic != MAGIC
                    || (flags & ~1) != 0
                    || reserved != 0
                    || count < 0
                    || count > PreviewMesh.MAX_PARTS
                    || length < 16
                    || length > MeshAppearanceCodec.MAX_BYTES
                    || size != HEADER_BYTES + count * 4L + length) {
                throw new IOException("Invalid GPU preview snapshot header");
            }
            byte[] expected = new byte[32];
            input.readFully(expected);
            var parts = new HashSet<Integer>();
            for (int i = 0; i < count; i++) {
                if (!parts.add(input.readInt())) {
                    throw new IOException("Duplicate GPU preview part");
                }
            }
            byte[] encoded = new byte[length];
            input.readFully(encoded);
            if (input.read() != -1
                    || !MessageDigest.isEqual(expected, digest(encoded, flags, parts))) {
                throw new IOException("GPU preview snapshot integrity mismatch");
            }
            try {
                if (flags == 1) {
                    var appearance = MeshAppearanceCodec.decode(encoded);
                    return new Payload(appearance.mesh(), appearance, parts);
                }
                return new Payload(PreviewMeshCodec.decode(encoded), null, parts);
            } catch (IllegalArgumentException failure) {
                throw new IOException("GPU preview snapshot contains invalid geometry", failure);
            }
        }
    }

    private static byte[] digest(byte[] bytes, int flags, Set<Integer> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var header = java.nio.ByteBuffer.allocate(24 + parts.size() * Integer.BYTES);
            header.putLong(MAGIC).putInt(flags).putInt(parts.size()).putInt(bytes.length).putInt(0);
            parts.stream().sorted().forEach(header::putInt);
            digest.update(header.array());
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
