package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import com.vibeminer.rendering.world.HorizonMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The World child's low-resolution horizon (#372): the map's WDL heights, rebuilt into a mesh on a
 * worker whenever the camera tile or the set of resident full-detail tiles changes. The render
 * thread only asks for a rebuild and takes a finished one, so it never waits for a build.
 *
 * <p>Vertices use the terrain mesh layout (float position, RGBA8 colour; 16 bytes). Colour is a
 * plain earth tone lit by the surface slope; the shared fog does the rest.
 */
final class WorldStreamHorizon implements AutoCloseable {
    /** Tiles around the camera tile: a 13 x 13 window, at most 169 tiles. */
    static final int RADIUS = 6;

    /** Fog reaches full by this eye depth when the horizon is on (option (a) of #372). */
    static final float FOG_END = (float) (RADIUS * WorldFormats.TILE_SIZE);

    /** GPU budget of one horizon: vertices and 32-bit indices of the full window with skirts. */
    static final int MAX_VERTICES = (2 * RADIUS + 1) * (2 * RADIUS + 1) * (545 + 4 * 17);

    static final int MAX_INDICES = (2 * RADIUS + 1) * (2 * RADIUS + 1) * (1024 + 128) * 3;
    static final long GPU_BUDGET_BYTES = MAX_VERTICES * 16L + MAX_INDICES * 4L;

    private static final int[] EARTH = {104, 100, 76};

    /** A finished horizon, ready to upload: vertices and indices in native order. */
    record Packed(
            ByteBuffer vertices,
            ByteBuffer indices,
            int vertexCount,
            int indexCount,
            int tiles,
            long buildNanos,
            long generation) {}

    private final WorldLowRes.LowRes lowRes;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-horizon-build").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lock = new Object();
    // Guarded by lock. The newest request; a finished build is kept only if nothing newer came.
    private long generation;
    private int requestedX = -1, requestedY = -1;
    private long[] requestedResident;
    private boolean building;
    private long[] pendingResident;
    private int pendingX, pendingY;
    private long pendingGeneration;
    private Packed ready;
    private volatile String lastFailure;

    WorldStreamHorizon(WorldLowRes.LowRes lowRes) {
        this.lowRes = Objects.requireNonNull(lowRes);
    }

    int tilesInWdl() {
        return lowRes.tiles().size();
    }

    /** The WDT tile coordinate that holds a native X or Y position. */
    static int tileOf(double nativeCoordinate) {
        return (int) Math.clamp(Math.floor(32 - nativeCoordinate / WorldFormats.TILE_SIZE), 0, 63);
    }

    /**
     * Asks for the horizon around the camera with these full-detail tiles hidden; cheap, and a
     * no-op when nothing changed. {@code resident} holds bit {@code x * 64 + y} per tile.
     */
    void request(double cameraX, double cameraY, long[] resident) {
        Objects.requireNonNull(resident);
        if (resident.length != 64) {
            throw new IllegalArgumentException("Resident tile mask");
        }
        int x = tileOf(cameraX), y = tileOf(cameraY);
        synchronized (lock) {
            if (closed.get()
                    || x == requestedX
                            && y == requestedY
                            && Arrays.equals(resident, requestedResident)) {
                return;
            }
            requestedX = x;
            requestedY = y;
            requestedResident = resident.clone();
            pendingX = x;
            pendingY = y;
            pendingResident = requestedResident;
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
        while (true) {
            int x, y;
            long built;
            long[] resident;
            synchronized (lock) {
                if (closed.get() || pendingResident == null) {
                    building = false;
                    return;
                }
                x = pendingX;
                y = pendingY;
                built = pendingGeneration;
                resident = pendingResident;
                pendingResident = null;
            }
            Packed packed;
            try {
                packed = build(lowRes, x, y, resident, built, closed::get);
            } catch (RuntimeException failed) {
                packed = null;
                lastFailure = failed.toString();
            }
            synchronized (lock) {
                if (packed != null && built == generation) {
                    ready = packed;
                }
            }
        }
    }

    static Packed build(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            long[] resident,
            long generation,
            java.util.function.BooleanSupplier canceled) {
        long started = System.nanoTime();
        var mesh =
                HorizonMesh.build(
                        lowRes,
                        cameraX,
                        cameraY,
                        RADIUS,
                        (x, y) -> (resident[(x * 64 + y) / 64] & 1L << ((x * 64 + y) % 64)) != 0,
                        canceled);
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
        var vertexBytes = ByteBuffer.allocateDirect(vertices * 16).order(ByteOrder.nativeOrder());
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
                generation);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
        }
    }
}
