package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import com.vibeminer.rendering.world.HorizonMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The World child's low-resolution horizon (#372): the map's WDL heights, rebuilt into a mesh on a
 * worker whenever the camera tile or the set of resident full-detail tiles changes. The render
 * thread only asks for a rebuild and takes a finished one, so it never waits for a build.
 *
 * <p>Vertices use the terrain mesh layout and its shared program ({@link #VERTEX_BYTES}, the stride
 * of {@link WorldStreamPackedTile#VERTEX_BYTES}): float position, then the RGBA8 colour, a plain
 * earth tone lit here by the surface slope; no MCCV (all zero); and the unlit normal marker, so the
 * terrain light (#373) leaves the colour as it is. The shared fog does the rest.
 */
final class WorldStreamHorizon implements AutoCloseable {
    /** Tiles around the camera tile: a 13 x 13 window, at most 169 tiles. */
    static final int RADIUS = 6;

    /** Fog reaches full by this eye depth when the horizon is on (option (a) of #372). */
    static final float FOG_END = (float) (RADIUS * WorldFormats.TILE_SIZE);

    /** One buffer set: vertices and 32-bit indices of the full window with skirts. */
    static final int MAX_VERTICES = (2 * RADIUS + 1) * (2 * RADIUS + 1) * (545 + 4 * 17);

    static final int MAX_INDICES = (2 * RADIUS + 1) * (2 * RADIUS + 1) * (1024 + 128) * 3;

    /**
     * Bytes per horizon vertex: the terrain mesh layout's stride (#560). The horizon's buffers are
     * created with that layout, so a vertex of any other size would be read at the wrong stride.
     */
    static final int VERTEX_BYTES = 24;

    /**
     * The horizon's fixed GPU budget: two buffer sets, one drawn while the next build uploads in
     * bounded steps into the other.
     */
    static final long GPU_BUDGET_BYTES =
            2 * ((long) MAX_VERTICES * VERTEX_BYTES + MAX_INDICES * 4L);

    /** Most bytes one upload step issues, so a rebuild never takes a frame's upload budget. */
    static final int STEP_BYTES = 512 << 10;

    private static final int[] EARTH = {104, 100, 76};

    /** A finished horizon, ready to upload: vertices and indices in native order. */
    record Packed(
            ByteBuffer vertices,
            ByteBuffer indices,
            int vertexCount,
            int indexCount,
            int tiles,
            long buildNanos,
            long generation,
            HorizonMesh.Ranges ranges) {}

    /** Consecutive failed builds after which this horizon stops drawing for the session. */
    static final int MAX_FAILURES = 3;

    /** One rebuild around a camera tile; {@link #build} outside tests. */
    @FunctionalInterface
    interface Builder {
        Packed build(
                WorldLowRes.LowRes lowRes,
                int cameraX,
                int cameraY,
                long generation,
                java.util.function.BooleanSupplier canceled);
    }

    private final WorldLowRes.LowRes lowRes;
    private final Builder builder;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-horizon-build").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lock = new Object();
    // Guarded by lock. The newest request; a finished build is kept only if nothing newer came.
    private long generation;
    private int requestedX = -1, requestedY = -1;
    private boolean building, pending;
    private int pendingX, pendingY, failures;
    private volatile boolean failed;
    private long pendingGeneration;
    private Packed ready;
    private volatile String lastFailure;

    WorldStreamHorizon(WorldLowRes.LowRes lowRes) {
        this(lowRes, WorldStreamHorizon::build);
    }

    WorldStreamHorizon(WorldLowRes.LowRes lowRes, Builder builder) {
        this.lowRes = Objects.requireNonNull(lowRes);
        this.builder = Objects.requireNonNull(builder);
    }

    int tilesInWdl() {
        return lowRes.tiles().size();
    }

    /** The WDT tile coordinate that holds a native X or Y position. */
    static int tileOf(double nativeCoordinate) {
        return (int) Math.clamp(Math.floor(32 - nativeCoordinate / WorldFormats.TILE_SIZE), 0, 63);
    }

    /**
     * Asks for the horizon around the camera; cheap, and a no-op while the camera stays in the same
     * tile. The mesh does not depend on which tiles are drawn at full detail: the renderer leaves
     * those out at draw time, so a resident change never waits for a rebuild.
     */
    void request(double cameraX, double cameraY) {
        int x = tileOf(cameraX), y = tileOf(cameraY);
        synchronized (lock) {
            if (closed.get() || failed || x == requestedX && y == requestedY) {
                return;
            }
            requestedX = x;
            requestedY = y;
            pendingX = x;
            pendingY = y;
            pending = true;
            pendingGeneration = ++generation;
            if (building) {
                return; // The running build picks up the newest request when it finishes.
            }
            building = true;
        }
        worker.execute(this::buildLoop);
    }

    /** The last build failure, or null; a failed build leaves the previous horizon drawn. */
    String lastFailure() {
        return lastFailure;
    }

    /**
     * Whether rebuilds failed {@link #MAX_FAILURES} times in a row; the renderer then stops drawing
     * this horizon, since it no longer follows the camera.
     */
    boolean failed() {
        return failed;
    }

    /** Whether a finished horizon is waiting to be taken, so the next frame should draw. */
    boolean hasReady() {
        synchronized (lock) {
            return ready != null;
        }
    }

    /** The newest finished horizon not yet taken, or null. Never blocks. */
    Packed take() {
        synchronized (lock) {
            var result = ready;
            ready = null;
            return result;
        }
    }

    private void buildLoop() {
        boolean finished = false;
        try {
            while (true) {
                int x, y;
                long built;
                synchronized (lock) {
                    if (closed.get() || failed || !pending) {
                        // Under the lock, so a request arriving now starts a new loop.
                        building = false;
                        finished = true;
                        return;
                    }
                    x = pendingX;
                    y = pendingY;
                    built = pendingGeneration;
                    pending = false;
                }
                Packed packed = null;
                try {
                    packed = builder.build(lowRes, x, y, built, closed::get);
                } catch (Throwable error) {
                    // Any failure, errors included, leaves the previous horizon in place.
                    lastFailure = error.toString();
                }
                synchronized (lock) {
                    if (packed == null) {
                        if (!closed.get() && ++failures >= MAX_FAILURES) {
                            failed = true;
                        }
                        // The next request asks again, even from the same camera tile.
                        requestedX = -1;
                    } else {
                        failures = 0;
                        if (built == generation) {
                            ready = packed;
                        }
                    }
                }
            }
        } finally {
            if (!finished) {
                synchronized (lock) {
                    building = false;
                }
            }
        }
    }

    static Packed build(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            long generation,
            java.util.function.BooleanSupplier canceled) {
        long started = System.nanoTime();
        var mesh = HorizonMesh.buildWindow(lowRes, cameraX, cameraY, RADIUS, canceled);
        int vertices = mesh.vertexCount(), indexCount = mesh.triangleCount() * 3;
        if (vertices > MAX_VERTICES || indexCount > MAX_INDICES) {
            throw new IllegalStateException("Horizon exceeds its GPU budget");
        }
        var positions = mesh.positions();
        var indices = mesh.indices();
        // Per-vertex normals from the faces around each vertex, for slope lighting only.
        float[] normals = new float[vertices * 3];
        for (int t = 0; t < indexCount; t += 3) {
            int a = indices.get(t), b = indices.get(t + 1), c = indices.get(t + 2);
            float ux = positions.get(b * 3) - positions.get(a * 3),
                    uy = positions.get(b * 3 + 1) - positions.get(a * 3 + 1),
                    uz = positions.get(b * 3 + 2) - positions.get(a * 3 + 2);
            float vx = positions.get(c * 3) - positions.get(a * 3),
                    vy = positions.get(c * 3 + 1) - positions.get(a * 3 + 1),
                    vz = positions.get(c * 3 + 2) - positions.get(a * 3 + 2);
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            if (nz == 0) {
                continue; // a vertical skirt: it must not darken the surface's edge
            }
            if (nz < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            for (int v : new int[] {a, b, c}) {
                normals[v * 3] += nx;
                normals[v * 3 + 1] += ny;
                normals[v * 3 + 2] += nz;
            }
        }
        var vertexBytes =
                ByteBuffer.allocateDirect(vertices * VERTEX_BYTES).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertices; v++) {
            float nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            // Light from high in the south-west; flat ground keeps most of the base colour.
            double light =
                    length == 0
                            ? 1
                            : 0.55 + 0.45 * Math.max(0, (0.3 * nx + 0.3 * ny + 0.9 * nz) / length);
            vertexBytes
                    .putFloat(positions.get(v * 3))
                    .putFloat(positions.get(v * 3 + 1))
                    .putFloat(positions.get(v * 3 + 2));
            for (int channel : EARTH) {
                vertexBytes.put((byte) Math.min(255, Math.round(channel * light)));
            }
            vertexBytes.put((byte) 255);
            // No MCCV, and the unlit marker: the slope light above is the horizon's only light.
            vertexBytes.putInt(0);
            vertexBytes
                    .put(WorldStreamPackedTile.UNLIT_NORMAL)
                    .put(WorldStreamPackedTile.UNLIT_NORMAL)
                    .put(WorldStreamPackedTile.UNLIT_NORMAL)
                    .put((byte) 255);
        }
        var indexBytes = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < indexCount; i++) {
            indexBytes.putInt(indices.get(i));
        }
        return new Packed(
                vertexBytes.flip(),
                indexBytes.flip(),
                vertices,
                indexCount,
                mesh.tiles(),
                System.nanoTime() - started,
                generation,
                mesh.ranges());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
        }
    }
}
