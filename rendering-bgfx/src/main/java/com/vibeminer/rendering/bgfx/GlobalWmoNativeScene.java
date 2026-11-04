package com.vibeminer.rendering.bgfx;

/**
 * Immutable standalone draw resources on the creating native thread. This interface allocates no
 * context, target, mapping or readback ring and grants no Source authority. The enclosing owner
 * drains submissions/readbacks, closes this scene, then actually shuts down its context.
 */
interface GlobalWmoNativeScene extends AutoCloseable {
    int WIDTH = 800, HEIGHT = 500;
    long CPU_MESH = 1_048_576, GPU_MESH = 851_968, QUEUED_MESH = 851_968;
    long DIRECT_MESH = 851_968, CPU_RGBA = 4_718_592, GPU_RGBA = 4_718_592;
    long QUEUED_RGBA = 4_718_592, DIRECT_IMAGE = 262_144;
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
