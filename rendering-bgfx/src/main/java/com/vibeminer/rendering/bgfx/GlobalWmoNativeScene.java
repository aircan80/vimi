package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.GlobalInteriorLimits;

/**
 * Immutable standalone draw resources on the creating native thread. This interface allocates no
 * context, target, mapping or readback ring and grants no Source authority. The enclosing owner
 * drains submissions/readbacks, closes this scene, then actually shuts down its context.
 */
interface GlobalWmoNativeScene extends AutoCloseable {
    int WIDTH = 800, HEIGHT = 500;

    /** Mesh bytes: 20-byte vertices plus 32-bit indices at the profile bounds. */
    long GPU_MESH =
            (long) GlobalInteriorLimits.VERTICES * 20
                    + (long) GlobalInteriorLimits.INDICES * WorldGlobalLayout.INDEX_BYTES;

    long CPU_MESH = GPU_MESH, QUEUED_MESH = GPU_MESH, DIRECT_MESH = GPU_MESH;
    long CPU_RGBA = (long) GlobalInteriorLimits.PIXELS * 4, GPU_RGBA = CPU_RGBA;
    long QUEUED_RGBA = CPU_RGBA, DIRECT_IMAGE = GlobalInteriorLimits.IMAGE_PIXELS * 4L;
    long FILE_BUFFER = 262_144, CONSTRUCTOR_COPY = 262_144, CODEC_COPY = 262_144;
    long GEOMETRY_SCRATCH = 1_048_576, RESOURCE_RESERVE = 2_097_152;
    long FRAME_OWNERS = 19_200_256;
    long TOTAL =
            CPU_MESH
                    + GPU_MESH
                    + QUEUED_MESH
                    + DIRECT_MESH
                    + CPU_RGBA
                    + GPU_RGBA
                    + QUEUED_RGBA
                    + DIRECT_IMAGE
                    + FILE_BUFFER
                    + CONSTRUCTOR_COPY
                    + CODEC_COPY
                    + GEOMETRY_SCRATCH
                    + RESOURCE_RESERVE
                    + FRAME_OWNERS;

    /**
     * Draws into the enclosing owner's already configured view0/target, with no clear or frame
     * advance. Source batch order, opaque RGB, two-sided strict GREATER, no fog/lighting.
     */
    void draw(GlobalWmoCamera camera, int width, int height);

    /** Queued bytes are conservative lifetime upload totals, not observed driver-release facts. */
    record Stats(
            long meshBytes,
            long imageBytes,
            long queuedMeshUpperBound,
            long queuedImageUpperBound,
            int liveStagingBytes,
            int peakMeshStagingBytes,
            int peakImageStagingBytes,
            int images,
            int batches) {}

    Stats stats();

    /** Owner-thread destruction only; actual native/context drain remains the enclosing owner's. */
    @Override
    void close();
}
