package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/** Single-credit worker preparation. Consumes decoded heap ownership; performs no bgfx calls. */
final class WorldStreamPackedTile implements AutoCloseable {
    static final int INSTANCE_BYTES = 48, MAX_WATER_TRIANGLES = 16384;
    static final int MAX_GEOMETRY_BYTES = (65536 + MAX_WATER_TRIANGLES) * INSTANCE_BYTES;
    // Untextured diagnostic teal (sRGB 20,115,148); not a claim of client liquid appearance.
    static final float WATER_R = 20, WATER_G = 115, WATER_B = 148;

    /** Transferred native input owner. Never freed before all make_ref_release credits drain. */
    static final class Source implements AutoCloseable {
        final WorldStreamTileFile.Header header;

        /** Terrain instances first, then waterTriangles water instances in the same buffer. */
        final int triangles, waterTriangles;

        ByteBuffer geometry, atlas;

        private Source(
                WorldStreamTileFile.Header header,
                int triangles,
                int waterTriangles,
                ByteBuffer geometry,
                ByteBuffer atlas) {
            this.header = header;
            this.triangles = triangles;
            this.waterTriangles = waterTriangles;
            this.geometry = geometry;
            this.atlas = atlas;
        }

        long payloadBytes() {
            return (geometry == null ? 0 : geometry.capacity())
                    + (long) (atlas == null ? 0 : atlas.capacity());
        }

        @Override
        public void close() {
            if (geometry != null) {
                MemoryUtil.memFree(geometry);
                geometry = null;
            }
            if (atlas != null) {
                MemoryUtil.memFree(atlas);
                atlas = null;
            }
        }
    }

    private Source owned;

    private WorldStreamPackedTile(Source owned) {
        this.owned = owned;
    }

    static WorldStreamPackedTile prepare(
            WorldStreamTileFile.Payload input, BooleanSupplier canceled) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(canceled);
        ByteBuffer geometry = null, atlas = null;
        try (input) {
            check(canceled);
            var tile = input.tile();
            var water = input.water();
            int waterTriangles = water == null ? 0 : water.indices().length / 3;
            if (waterTriangles > MAX_WATER_TRIANGLES) {
                throw new IllegalArgumentException("Packed water exceeds one-credit limit");
            }
            int bytes = Math.multiplyExact(tile.triangleCount() + waterTriangles, INSTANCE_BYTES);
            if (bytes > MAX_GEOMETRY_BYTES) {
                throw new IllegalArgumentException("Packed tile exceeds one-credit geometry limit");
            }
            if (bytes != 0) {
                geometry = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
                var out = geometry.asFloatBuffer();
                var vertices = tile.vertices();
                var indices = tile.indices();
                for (int at = 0; at < tile.indexCount(); at += 3) {
                    if ((at & 4095) == 0) {
                        check(canceled);
                    }
                    int a = indices.get(at) * 3,
                            b = indices.get(at + 1) * 3,
                            c = indices.get(at + 2) * 3;
                    float ax = vertices.get(a), ay = vertices.get(a + 1), az = vertices.get(a + 2);
                    float bx = vertices.get(b), by = vertices.get(b + 1), bz = vertices.get(b + 2);
                    float cx = vertices.get(c), cy = vertices.get(c + 1), cz = vertices.get(c + 2);
                    // Preserve the legacy native float subtraction before the double shade math.
                    double ux = bx - ax, uy = by - ay, uz = bz - az;
                    double vx = cx - ax, vy = cy - ay, vz = cz - az;
                    double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                    double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    double light =
                            length == 0
                                    ? .7
                                    : .45
                                            + .55
                                                    * Math.abs(
                                                            (nx * .3 + ny * .4 + nz * .866)
                                                                    / length);
                    double altitude = (az + bz + cz) / 3.0;
                    double rock = Math.clamp((altitude - 150) / 600, 0, 1);
                    corner(out, ax, ay, az, (72 + rock * 65) * light);
                    corner(out, bx, by, bz, (116 + rock * 10) * light);
                    corner(out, cx, cy, cz, (68 + rock * 57) * light);
                }
                if (water != null) {
                    // The stream shader takes the three corner w values as the triangle's RGB.
                    var positions = water.positions();
                    var cells = water.indices();
                    for (int at = 0; at < cells.length; at += 3) {
                        if ((at & 4095) == 0) {
                            check(canceled);
                        }
                        waterCorner(out, positions, cells[at], WATER_R);
                        waterCorner(out, positions, cells[at + 1], WATER_G);
                        waterCorner(out, positions, cells[at + 2], WATER_B);
                    }
                }
            }
            var pixels = input.atlas();
            if (pixels != null) {
                atlas = MemoryUtil.memAlloc(WorldStreamTileFile.ATLAS_BYTES);
                for (int at = 0; at < pixels.remaining(); at += 64 << 10) {
                    check(canceled);
                    int count = Math.min(64 << 10, pixels.remaining() - at);
                    atlas.put(pixels.duplicate().position(at).limit(at + count));
                }
                atlas.flip();
            }
            check(canceled);
            var result =
                    new WorldStreamPackedTile(
                            new Source(
                                    input.header(),
                                    tile.triangleCount(),
                                    waterTriangles,
                                    geometry,
                                    atlas));
            geometry = null;
            atlas = null;
            return result;
        } finally {
            if (geometry != null) {
                MemoryUtil.memFree(geometry);
            }
            if (atlas != null) {
                MemoryUtil.memFree(atlas);
            }
        }
    }

    synchronized Source take() {
        if (owned == null) {
            throw new IllegalStateException("Packed input consumed or closed");
        }
        var result = owned;
        owned = null;
        return result;
    }

    @Override
    public synchronized void close() {
        if (owned != null) {
            owned.close();
            owned = null;
        }
    }

    private static void waterCorner(FloatBuffer out, float[] positions, int vertex, float color) {
        out.put(positions[vertex * 3])
                .put(positions[vertex * 3 + 1])
                .put(positions[vertex * 3 + 2])
                .put(color);
    }

    private static void corner(FloatBuffer out, float x, float y, float z, double color) {
        out.put(x).put(y).put(z).put((float) color);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
