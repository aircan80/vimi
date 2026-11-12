package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_dynamic_vertex_buffer;

import com.vibeminer.rendering.models.PreviewAnimation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Idle animation of World object models (#384): creature models whose objects file carries an idle
 * sequence play it on the CPU, as the walking body does ({@link WorldStreamBodyDraw}), instead of
 * standing in the pose baked at 0 ms.
 *
 * <p>Each animated model gets up to {@link #PHASES} poses, one per phase of its loop, each a
 * dynamic vertex buffer re-skinned on the owner thread when its time advances by a {@link
 * #STEP_MILLIS} step. Every placement plays the phase its position chooses ({@link #phase}), so a
 * crowd of one model does not move in lockstep while skinning stays bounded by models times phases,
 * not placements. Only placements within {@link #RADIUS} of the camera animate, at most {@link
 * #FRAME_INSTANCES} a frame; skinning is bounded by {@link #FRAME_SKIN_VERTICES} a frame, and the
 * poses' buffers by {@link #GPU_BYTES}. A placement outside these bounds draws its static pose, so
 * a far or crowded scene degrades to what it was before. Nothing here is specific to a creature,
 * model or map.
 */
final class WorldObjectAnimation {
    private WorldObjectAnimation() {}

    /** Poses per animated model: the phases its placements are spread over. */
    static final int PHASES = 4;

    /** The animation clock's step: a pose is re-skinned at most this often (25 poses a second). */
    static final long STEP_MILLIS = 40;

    /**
     * While a frame drew animated placements, the child redraws an unchanged camera this often
     * (about 12 frames a second, the walking body's idle cadence), so the idle keeps playing.
     */
    static final long REDRAW_MILLIS = 80;

    /** Placements farther than this from the camera (yards) draw their static pose. */
    static final double RADIUS = 100;

    /** Placements drawn animated per frame, nearest tiles first; the rest draw static. */
    static final int FRAME_INSTANCES = 384;

    /**
     * Vertices re-skinned per frame. A pose over the budget keeps its last skin (a step late) or,
     * when it has none yet, its placements draw static this frame. A re-skin (bone matrices,
     * skinning, gathering and upload) measured about 41 ns a vertex on 70862 creatures around
     * Goldshire, so the budget bounds skinning near 4 ms a frame; a humanoid NPC's four poses take
     * about 19,000 vertices of it.
     */
    static final int FRAME_SKIN_VERTICES = 100_000;

    /** GPU bytes all poses' vertex buffers may hold, inside the session's object budget. */
    static final long GPU_BYTES = 24L << 20;

    /**
     * The phase a placement at {@code (x, y)} plays: a hash of its position, so it is the same
     * every frame and on every residency of its tile, and neighbours differ.
     */
    static int phase(double x, double y) {
        // Eighth-yard cells, mixed with SplitMix64's finalizer so every bit reaches the low ones.
        long bits = (long) Math.rint(x * 8) * 0x9E3779B97F4A7C15L + (long) Math.rint(y * 8);
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return Math.floorMod(bits, PHASES);
    }

    /**
     * Where in a loop of {@code duration} ms phase {@code phase} is at clock {@code clock} ms: the
     * clock stepped down to {@link #STEP_MILLIS}, offset by the phase's share of the loop.
     */
    static long millis(long clock, long duration, int phase) {
        if (duration <= 0) {
            return 0;
        }
        long stepped = Math.max(0, clock) / STEP_MILLIS * STEP_MILLIS;
        return Math.floorMod(stepped + duration * phase / PHASES, duration);
    }

    /**
     * Animations parsed by any set of this child, by the SHA-256 of their bytes, held weakly: the
     * same creature on several resident tiles (each tile's objects file carries its own copy)
     * shares one parsed animation, which goes once no set holds it.
     */
    private static final java.util.Map<String, java.lang.ref.WeakReference<PreviewAnimation>>
            PARSED = new java.util.HashMap<>();

    /** Parsed animations reused from another set (diagnostics). */
    static final java.util.concurrent.atomic.AtomicLong REUSED =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The animation {@code bytes} hold ({@link PreviewAnimation#write}), shared with any set that
     * parsed the same bytes, or null when they do not read as one.
     */
    static PreviewAnimation parse(byte[] bytes) {
        String key;
        try {
            key =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
        synchronized (PARSED) {
            var held = PARSED.get(key);
            var animation = held == null ? null : held.get();
            if (animation != null) {
                REUSED.incrementAndGet();
                return animation;
            }
        }
        PreviewAnimation animation;
        try {
            animation =
                    PreviewAnimation.read(
                            new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return null;
        }
        synchronized (PARSED) {
            PARSED.values().removeIf(reference -> reference.get() == null);
            PARSED.put(key, new java.lang.ref.WeakReference<>(animation));
        }
        return animation;
    }

    /** GPU bytes of one pose of {@code animation}'s model: its vertices in the objects' layout. */
    static long bytes(WorldStreamPackedObjects.ObjectAnimation animation) {
        return (long) animation.vertexCount() * WorldStreamPackedObjects.GPU_VERTEX_BYTES;
    }

    /**
     * One phase's pose of one animated model (owner thread only): a dynamic vertex buffer in the
     * objects' layout, re-skinned from reused arrays and native memory, so a re-skin allocates
     * nothing on the Java heap.
     */
    static final class Pose implements AutoCloseable {
        final short vertices;
        private final WorldStreamPackedObjects.ObjectAnimation source;
        private final PreviewAnimation.Skinner skinner;
        private final float[] positions, normals;
        private final ByteBuffer staging, rest;
        private long skinned = -1;
        private boolean closed;

        private Pose(WorldStreamPackedObjects.ObjectAnimation source, short vertices) {
            this.source = source;
            this.vertices = vertices;
            skinner = source.animation.skinner();
            int surface = source.animation.vertexCount();
            positions = new float[surface * 3];
            normals = source.animation.hasNormals() ? new float[surface * 3] : null;
            staging =
                    MemoryUtil.memAlloc(
                                    source.vertexCount()
                                            * WorldStreamPackedObjects.GPU_VERTEX_BYTES)
                            .order(ByteOrder.nativeOrder());
            staging.put(source.vertices).flip();
            rest = ByteBuffer.wrap(source.vertices).order(ByteOrder.nativeOrder());
        }

        /** A new pose's buffer, or null when bgfx has no dynamic vertex buffer left. */
        static Pose create(
                WorldStreamPackedObjects.ObjectAnimation source,
                org.lwjgl.bgfx.BGFXVertexLayout layout) {
            short handle =
                    bgfx_create_dynamic_vertex_buffer(
                            source.vertexCount(), layout, BGFX_BUFFER_NONE);
            if (handle == (short) 0xffff) {
                return null;
            }
            return new Pose(source, handle);
        }

        /** The loop time last skinned, or -1 before the first skin. */
        long skinned() {
            return skinned;
        }

        /** Skins this pose at {@code millis} and uploads it. */
        void skin(long millis) {
            skinner.skin(millis, positions, normals);
            gather(rest, source.sources, positions, normals, staging);
            long memory =
                    org.lwjgl.bgfx.BGFX.nbgfx_copy(
                            MemoryUtil.memAddress(staging), staging.capacity());
            org.lwjgl.bgfx.BGFX.nbgfx_update_dynamic_vertex_buffer(vertices, 0, memory);
            skinned = millis;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            bgfx_destroy_dynamic_vertex_buffer(vertices);
            MemoryUtil.memFree(staging);
        }
    }

    /**
     * Writes each model vertex's skinned position and normal (its baked normal when the rest
     * surface records none) over its GPU-layout copy in {@code out}, keeping its texture coordinate
     * and colour. Allocates nothing.
     */
    static void gather(
            ByteBuffer rest, int[] sources, float[] positions, float[] normals, ByteBuffer out) {
        int stride = WorldStreamPackedObjects.GPU_VERTEX_BYTES;
        for (int v = 0; v < sources.length; v++) {
            int at = v * stride, s = sources[v] * 3;
            out.putFloat(at, positions[s]);
            out.putFloat(at + 4, positions[s + 1]);
            out.putFloat(at + 8, positions[s + 2]);
            if (normals != null) {
                out.putFloat(at + 12, normals[s]);
                out.putFloat(at + 16, normals[s + 1]);
                out.putFloat(at + 20, normals[s + 2]);
            } else {
                out.putFloat(at + 12, rest.getFloat(at + 12));
                out.putFloat(at + 16, rest.getFloat(at + 16));
                out.putFloat(at + 20, rest.getFloat(at + 20));
            }
        }
    }
}
