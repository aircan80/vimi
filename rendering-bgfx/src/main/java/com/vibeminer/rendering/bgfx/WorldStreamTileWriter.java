package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Source-neutral structural writer. Only the application's closed source bridge qualifies assets.
 */
public final class WorldStreamTileWriter {
    public enum Outcome {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    public enum Reason {
        NONE,
        TERMINAL_MATERIAL_UNAVAILABLE,
        QUALIFIED_BINDING_REJECTED
    }

    public enum Encoding {
        PALETTED,
        BC1,
        BC2,
        BC3,
        BGRA8
    }

    /** Descriptive consumed-file/mip tuple; construction grants no source authority. */
    public record Source(
            long fileDataId,
            String contentKey,
            long consumedLength,
            Long declaredLength,
            int sourceLimit,
            int originalSide,
            int mipLevel,
            int mipFlags,
            Encoding encoding,
            int alphaDepth,
            int alphaEncoding) {
        public Source {
            new WorldStreamTileFile.Source(
                    fileDataId,
                    contentKey,
                    consumedLength,
                    declaredLength,
                    sourceLimit,
                    originalSide,
                    mipLevel,
                    mipFlags,
                    encoding == null ? null : WorldStreamTileFile.Encoding.valueOf(encoding.name()),
                    alphaDepth,
                    alphaEncoding);
        }

        private WorldStreamTileFile.Source wire() {
            return new WorldStreamTileFile.Source(
                    fileDataId,
                    contentKey,
                    consumedLength,
                    declaredLength,
                    sourceLimit,
                    originalSide,
                    mipLevel,
                    mipFlags,
                    encoding == null ? null : WorldStreamTileFile.Encoding.valueOf(encoding.name()),
                    alphaDepth,
                    alphaEncoding);
        }
    }

    /** Bounded detached descriptions. A cache key is deliberately not an input. */
    public static final class Data {
        private final String scope, profile, geometry;
        private final Outcome outcome;
        private final Reason reason;
        private final long[] coverage;
        private final float maxX, maxY;
        private final double uvError;
        private final WorldStreamTileFile.Source wdt, root, tex0;
        private final List<WorldStreamTileFile.Source> diffuse;

        public Data(
                String scopeSha256,
                String profile,
                String geometrySha256,
                Outcome outcome,
                Reason reason,
                long[] coverage,
                float nativeMaxX,
                float nativeMaxY,
                double maxUvError,
                Source wdt,
                Source root,
                Source tex0,
                List<Source> diffuse) {
            if (!hex(scopeSha256)
                    || !hex(geometrySha256)
                    || profile == null
                    || profile.isEmpty()
                    || profile.length() > 128) {
                throw new IllegalArgumentException("Packet description identity bounds");
            }
            for (int i = 0; i < profile.length(); i++) {
                if (profile.charAt(i) < 0x21 || profile.charAt(i) > 0x7e) {
                    throw new IllegalArgumentException("Packet profile ASCII");
                }
            }
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(diffuse);
            int count = diffuse.size();
            if (coverage.length != 4 || count > 64 || count < 0) {
                throw new IllegalArgumentException("Packet coverage/source bounds");
            }
            var captured = new WorldStreamTileFile.Source[count];
            for (int i = 0; i < count; i++) {
                captured[i] = Objects.requireNonNull(diffuse.get(i)).wire();
            }
            this.diffuse = List.of(captured);
            this.wdt = Objects.requireNonNull(wdt).wire();
            this.root = Objects.requireNonNull(root).wire();
            this.tex0 = tex0 == null ? null : tex0.wire();
            this.scope = scopeSha256;
            this.profile = profile;
            this.geometry = geometrySha256;
            this.outcome = Objects.requireNonNull(outcome);
            this.reason = Objects.requireNonNull(reason);
            this.coverage = coverage.clone();
            maxX = nativeMaxX;
            maxY = nativeMaxY;
            uvError = maxUvError;
        }

        private WorldStreamTileFile.Header header(
                WorldStreamTransport.PacketDestination.Context context,
                TerrainScene.Tile tile,
                String materialDigest) {
            return new WorldStreamTileFile.Header(
                    context.nonce(),
                    scope,
                    context.transaction(),
                    context.lease(),
                    tile.x(),
                    tile.y(),
                    tile.chunks(),
                    tile.vertexCount(),
                    tile.indexCount(),
                    geometry,
                    materialDigest,
                    WorldStreamTileFile.Outcome.valueOf(outcome.name()),
                    WorldStreamTileFile.Reason.valueOf(reason.name()),
                    coverage,
                    maxX,
                    maxY,
                    uvError,
                    wdt,
                    root,
                    tex0,
                    diffuse);
        }
    }

    /** Source R owns this unadvertised file until its independently pinned one-shot MOVE. */
    public static final class PacketFile implements AutoCloseable {
        private final WorldStreamPacketFile file;
        private WorldStreamTransport.PacketDestination destination;
        private WorldStreamTileFile.Header writtenHeader;

        private PacketFile(
                WorldStreamPacketFile file,
                WorldStreamTransport.PacketDestination destination,
                WorldStreamTileFile.Header writtenHeader) {
            this.file = file;
            this.destination = destination;
            this.writtenHeader = writtenHeader;
        }

        /** Off FX, after source final checks, inside the source's accepted resource callback. */
        public WorldStreamTransport.OwnedPacket move() {
            WorldStreamPacketFile.Moved moved;
            WorldStreamTransport.PacketDestination target;
            WorldStreamTileFile.Header header;
            synchronized (this) {
                if (destination == null) {
                    throw new IllegalStateException("Packet file already moved or closed");
                }
                moved = file.move();
                target = destination;
                header = writtenHeader;
                destination = null;
                writtenHeader = null;
            }
            try {
                return target.adopt(moved, header);
            } catch (RuntimeException | Error failure) {
                try {
                    moved.close();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                destination = null;
                writtenHeader = null;
            }
            file.close();
        }
    }

    /**
     * Off-FX synchronous structural IO inside the actual source admission. The caller pins exact
     * geometry/atlas bytes through return. Only its source engine may authorize the final handoff.
     */
    public static PacketFile writeUnadvertised(
            WorldStreamTransport.PacketDestination destination,
            Data data,
            TerrainScene.Tile geometry,
            TerrainTextureBinding texture,
            BooleanSupplier stopping)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(data);
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(stopping);
        var context = destination.context();
        if (!data.scope.equals(context.scope())
                || geometry.x() != context.x()
                || geometry.y() != context.y()) {
            throw new IllegalArgumentException("Packet source scope/tile differs from destination");
        }
        if (stopping.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
        WorldStreamTileFile.Header header;
        if (data.outcome == Outcome.TEXTURED) {
            Objects.requireNonNull(texture);
            var mapping = texture.metadata();
            if (mapping.tileX() != geometry.x()
                    || mapping.tileY() != geometry.y()
                    || !mapping.rootGeometrySha256().equals(data.geometry)
                    || mapping.sources().rootFileDataId() != data.root.id()
                    || !mapping.sources().rootContentKey().equals(data.root.contentKey())
                    || data.tex0 == null
                    || mapping.sources().tex0FileDataId() != data.tex0.id()
                    || !mapping.sources().tex0ContentKey().equals(data.tex0.contentKey())
                    || Float.floatToRawIntBits(mapping.nativeMaxX())
                            != Float.floatToRawIntBits(data.maxX)
                    || Float.floatToRawIntBits(mapping.nativeMaxY())
                            != Float.floatToRawIntBits(data.maxY)
                    || Double.doubleToRawLongBits(mapping.maxSourceUvErrorTexels())
                            != Double.doubleToRawLongBits(data.uvError)) {
                throw new IllegalArgumentException("Packet material differs from checked mapping");
            }
            var coverage = mapping.rectangleCoverage();
            for (int i = 0; i < 4; i++) {
                if (coverage.get(i) != data.coverage[i]) {
                    throw new IllegalArgumentException(
                            "Packet coverage differs from checked mapping");
                }
            }
            // The template's opaque digest is never published; the actual payload identity is
            // computed incrementally from these exact descriptions and pinned pixels.
            var template = data.header(context, geometry, "ff".repeat(32));
            String digest =
                    WorldStreamMaterialDigest.compute(
                            template, texture.pixels(), data.profile, stopping);
            header = data.header(context, geometry, digest);
        } else {
            if (texture != null) {
                throw new IllegalArgumentException("Height packet has an atlas");
            }
            header = data.header(context, geometry, null);
        }
        WorldStreamTileFile.Expected expected = null;
        WorldStreamPacketFile owner = null;
        try {
            expected =
                    WorldStreamTileFile.write(
                            context.path().getParent(),
                            header,
                            geometry,
                            texture == null ? null : texture.pixels(),
                            stopping);
            owner = WorldStreamPacketFile.finalized(expected, context.path(), context.identity());
            return new PacketFile(owner, destination, header);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (owner != null) {
                    owner.close();
                } else if (expected != null) {
                    Files.deleteIfExists(context.path());
                }
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static boolean hex(String value) {
        return value != null && value.length() == 64 && value.matches("[0-9a-f]{64}");
    }

    private WorldStreamTileWriter() {}
}
