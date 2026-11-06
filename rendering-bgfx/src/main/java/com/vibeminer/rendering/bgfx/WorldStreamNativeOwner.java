package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_BLIT;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_stats;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Native-thread fixed-resource owner. No stdin, statuses, paths, worker, watchdog or GUI wiring.
 */
final class WorldStreamNativeOwner implements AutoCloseable {
    private static final short INVALID = -1;
    private static final int WIDTH = 800, HEIGHT = 500, PIXELS = WIDTH * HEIGHT * 4;
    private static final AtomicBoolean CONTEXT = new AtomicBoolean();

    private static final class Readback {
        short texture = INVALID;
        ByteBuffer pixels;
        BgfxWorldStreamRenderer.Ticket ticket;
        long order;
        int ready;
    }

    record Stats(
            BgfxWorldStreamRenderer.Stats upload,
            int dynamicBuffers,
            int backingBuffers,
            int textures,
            int contextBuffers,
            int contextTextures,
            long contextGpuHeap,
            long publishedSequence,
            int pendingReadbacks,
            long gpuHeapBytes,
            int msaaSamples) {}

    /** Reset flags: no back buffer; anisotropic samplers use the device maximum (issue #357). */
    static final int RESET_FLAGS = org.lwjgl.bgfx.BGFX.BGFX_RESET_MAXANISOTROPY;

    /** Samples of the World render target when the device can multisample it. */
    static final int MSAA_SAMPLES = 4;

    /**
     * The target's sample count: {@link #MSAA_SAMPLES} when both the colour and depth formats can
     * be multisampled render targets, else 1 (single-sampled, as before).
     */
    static int targetSamples(boolean colourMultisampled, boolean depthMultisampled) {
        return colourMultisampled && depthMultisampled ? MSAA_SAMPLES : 1;
    }

    /**
     * Whether a format can be a multisampled render target: its bgfx format caps must carry
     * BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER_MSAA (the primary check; texture validation is not
     * relied on to test the MSAA bits) and bgfx must accept the texture with those flags.
     */
    static boolean multisampled(int formatCaps, boolean textureValid) {
        return (formatCaps & org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER_MSAA) != 0
                && textureValid;
    }

    /**
     * Colour target flags. A multisampled colour target keeps its resolve texture (not write only):
     * bgfx resolves it when view 0 ends, and the readback blit in view 1 copies the resolved
     * pixels.
     */
    static long colourFlags(int samples) {
        return samples > 1
                ? BGFX_TEXTURE_RT | org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MSAA_X4
                : BGFX_TEXTURE_RT;
    }

    /** Depth target flags; multisampled depth is never read back, so it is write only. */
    static long depthFlags(int samples) {
        return samples > 1
                ? BGFX_TEXTURE_RT
                        | org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MSAA_X4
                        | org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY
                : BGFX_TEXTURE_RT;
    }

    private final Thread owner = Thread.currentThread();
    private final WorldStreamEnvelope.Session session;
    private final WorldFrameFileV2 frames;
    private final Readback[] readbacks = new Readback[3];
    private BgfxWorldStreamRenderer renderer;
    private short color = INVALID, depth = INVALID, target = INVALID;
    private ByteBuffer cropped;
    private WorldStreamControl.Camera lastCamera;
    private long cameraRevision, policy, submittedCamera, submittedContent = -1, submittedPolicy;
    private long submittedObjects;
    private long publishedCamera, publishedContent, sequence, order;
    private int width, height, currentFrame, contextBuffers, contextTextures, samples = 1;
    private long contextGpuHeap;
    private boolean initialized, acquired, closed, renderedThisFrame;

    WorldStreamNativeOwner(
            WorldStreamEnvelope.Session session, WorldFrameFileV2 frames, int backend)
            throws IOException {
        this.session = Objects.requireNonNull(session);
        this.frames = Objects.requireNonNull(frames);
        if (!frames.freshFor(session)) {
            throw new IOException("Frame mapping is not fresh for this session");
        }
        if (!CONTEXT.compareAndSet(false, true)) {
            throw new IOException("Native World context already owned");
        }
        acquired = true;
        try {
            try {
                GpuRuntimeProbe.verifyShaders("world-stream/");
                GpuRuntimeProbe.verifyShaders("terrain/sky/");
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            try (var stack = MemoryStack.stackPush()) {
                var init = BGFXInit.calloc(stack);
                bgfx_init_ctor(init);
                init.type(backend).fallback(false);
                // Offscreen context only; one-frame command latency is explicitly qualified by
                // proof.
                init.resolution().width(0).height(0).reset(RESET_FLAGS).maxFrameLatency((byte) 1);
                BGFXPlatform.bgfx_render_frame(0);
                if (!bgfx_init(init)) {
                    throw new IOException("Headless World backend unavailable");
                }
                initialized = true;
            }
            var caps = bgfx_get_caps();
            GpuViewerMain.requireHardware(
                    Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
            if ((caps.supported() & (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK))
                            != (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK)
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_RT)
                    || !bgfx_is_texture_valid(
                            0,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_BGRA8,
                            BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK)) {
                throw new IOException("World readback capabilities unavailable");
            }
            bgfx_frame(BGFX_FRAME_NONE);
            bgfx_frame(BGFX_FRAME_NONE);
            contextBuffers = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
            contextTextures = Short.toUnsignedInt(bgfx_get_stats().numTextures());
            contextGpuHeap = bgfx_get_stats().gpuMemoryUsed();
            renderer = new BgfxWorldStreamRenderer(session);
            samples =
                    targetSamples(
                            multisampled(
                                    caps.formats(BGFX_TEXTURE_FORMAT_BGRA8),
                                    bgfx_is_texture_valid(
                                            0,
                                            false,
                                            1,
                                            BGFX_TEXTURE_FORMAT_BGRA8,
                                            colourFlags(MSAA_SAMPLES))),
                            multisampled(
                                    caps.formats(BGFX_TEXTURE_FORMAT_D32F),
                                    bgfx_is_texture_valid(
                                            0,
                                            false,
                                            1,
                                            BGFX_TEXTURE_FORMAT_D32F,
                                            depthFlags(MSAA_SAMPLES))));
            renderer.targetSamples(samples);
            if (samples == 1) {
                // A device fact, not a failure: the World draws single-sampled, as before.
                System.err.println("World render target: multisampling unavailable, 1 sample");
                System.err.flush();
            }
            color =
                    valid(
                            bgfx_create_texture_2d(
                                    WIDTH,
                                    HEIGHT,
                                    false,
                                    1,
                                    BGFX_TEXTURE_FORMAT_BGRA8,
                                    colourFlags(samples),
                                    null,
                                    0));
            depth =
                    valid(
                            bgfx_create_texture_2d(
                                    WIDTH,
                                    HEIGHT,
                                    false,
                                    1,
                                    BGFX_TEXTURE_FORMAT_D32F,
                                    depthFlags(samples),
                                    null,
                                    0));
            target =
                    valid(bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false));
            for (int i = 0; i < readbacks.length; i++) {
                var slot = new Readback();
                readbacks[i] = slot;
                slot.pixels = MemoryUtil.memAlloc(PIXELS);
                slot.texture =
                        valid(
                                bgfx_create_texture_2d(
                                        WIDTH,
                                        HEIGHT,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_BGRA8,
                                        BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                                        null,
                                        0));
            }
            cropped = MemoryUtil.memAlloc(PIXELS);
            bgfx_set_view_frame_buffer(0, target);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    BgfxWorldStreamRenderer.Upload begin(WorldStreamPackedTile input) {
        requireOpen();
        return renderer.begin(input);
    }

    int step(BgfxWorldStreamRenderer.Upload upload) {
        requireOpen();
        return renderedThisFrame ? 0 : renderer.step(upload);
    }

    void cancel(BgfxWorldStreamRenderer.Upload upload) {
        requireOpen();
        renderer.cancel(upload);
    }

    BgfxWorldStreamRenderer.LateUpload beginLate(
            WorldStreamState.Lease lease, WorldStreamPackedObjects objects) {
        try {
            requireOpen();
        } catch (RuntimeException closed) {
            objects.close();
            throw closed;
        }
        return renderer.beginLate(lease, objects);
    }

    /** Same frame rule as terrain: no GPU mutation after this frame's draw submission. */
    int stepLate(BgfxWorldStreamRenderer.LateUpload upload) {
        requireOpen();
        return renderedThisFrame ? 0 : renderer.stepLate(upload);
    }

    WorldStreamStatus.LateOutcome finishLate(
            BgfxWorldStreamRenderer.LateUpload upload, boolean live) {
        requireOpen();
        return renderer.finishLate(upload, live);
    }

    /** Proof observation of the renderer's splat state. */
    boolean splatted(int slot) {
        requireOpen();
        return renderer.splatted(slot);
    }

    /** Proof observation: RGBA8 bytes of every slot's splat mask chain on the GPU. */
    long splatMaskBytes() {
        requireOpen();
        return renderer.splatMaskBytes();
    }

    boolean splatBlocks() {
        requireOpen();
        return renderer.splatBlocks();
    }

    boolean splatArrayCreated() {
        requireOpen();
        return renderer.splatArrayCreated();
    }

    /** Test seam for native proofs; see {@link BgfxWorldStreamRenderer#objectBudgetForProof}. */
    void objectBudgetForProof(long bytes) {
        requireOpen();
        renderer.objectBudgetForProof(bytes);
    }

    void commit(long expected, long next, List<WorldStreamState.Lease> table) {
        requireOpen();
        renderer.commit(expected, next, table);
    }

    boolean submit(WorldStreamControl.Camera command) {
        requireOpen();
        if (!session.nonce().equals(command.nonce())
                || command.revision() < cameraRevision
                || command.policy() < session.policy()
                || command.policy() < policy
                || command.policy() == policy
                        && (command.width() != width || command.height() != height)
                || command.policy() > policy && command.revision() <= cameraRevision) {
            throw new IllegalArgumentException("Stale World camera/policy");
        }
        if (lastCamera != null
                && command.revision() == cameraRevision
                && !command.equals(lastCamera)) {
            throw new IllegalArgumentException("Camera revision relabeled different controls");
        }
        lastCamera = command;
        cameraRevision = command.revision();
        policy = command.policy();
        width = command.width();
        height = command.height();
        if (submittedCamera == cameraRevision
                && submittedContent == renderer.contentRevision()
                && submittedObjects == renderer.objectsRevision()
                && submittedPolicy == policy) {
            return false;
        }
        if (renderedThisFrame) {
            return false;
        }
        Readback available = null;
        for (var slot : readbacks) {
            if (slot.ticket == null) {
                available = slot;
                break;
            }
        }
        if (available == null) {
            return false;
        }
        if (order == WorldFrameFileV2.MAX_SEQUENCE) {
            throw new IllegalStateException("Frame order exhausted");
        }
        var ticket = renderer.draw(command.camera(), cameraRevision, policy, width, height);
        bgfx_blit(1, available.texture, 0, 0, 0, 0, color, 0, 0, 0, 0, width, height, 1);
        available.pixels.clear();
        available.ticket = ticket;
        available.order = ++order;
        available.ready = bgfx_read_texture(available.texture, available.pixels, 0, 0);
        renderedThisFrame = true;
        submittedCamera = cameraRevision;
        submittedContent = ticket.content();
        submittedObjects = renderer.objectsRevision();
        submittedPolicy = policy;
        return true;
    }

    boolean advance() throws IOException {
        requireOpen();
        currentFrame = bgfx_frame(BGFX_FRAME_NONE);
        renderedThisFrame = false;
        renderer.pollUploads();
        Readback newest = null;
        for (var slot : readbacks) {
            var ticket = slot.ticket;
            if (ticket != null
                    && readbackReady(currentFrame, slot.ready)
                    && ticket.policy() == policy
                    && ticket.width() == width
                    && ticket.height() == height
                    && ticket.camera() >= publishedCamera
                    && ticket.content() >= publishedContent
                    && (newest == null || slot.order > newest.order)) {
                newest = slot;
            }
        }
        boolean published = false;
        if (newest != null) {
            if (sequence == WorldFrameFileV2.MAX_SEQUENCE) {
                throw new IOException("Frame sequence exhausted");
            }
            var ticket = newest.ticket;
            cropped.clear();
            int stride = WIDTH * 4, length = ticket.width() * 4;
            boolean flip = bgfx_get_caps().originBottomLeft();
            for (int row = 0; row < ticket.height(); row++) {
                int sourceRow = flip ? HEIGHT - 1 - row : row;
                cropped.put(
                        newest.pixels
                                .duplicate()
                                .position(sourceRow * stride)
                                .limit(sourceRow * stride + length));
            }
            cropped.flip();
            frames.write(
                    new WorldStreamFrameGate.Frame(
                            session.nonce(),
                            ++sequence,
                            ticket.camera(),
                            ticket.content(),
                            ticket.policy(),
                            ticket.width(),
                            ticket.height()),
                    cropped);
            publishedCamera = ticket.camera();
            publishedContent = ticket.content();
            published = true;
        }
        for (var slot : readbacks) {
            if (slot.ticket != null && readbackReady(currentFrame, slot.ready)) {
                slot.ticket = null;
            }
        }
        return published;
    }

    /** Sequence of the newest frame written to the mapping, or 0 before the first. */
    long publishedSequence() {
        requireOpen();
        return sequence;
    }

    // A readback is scheduled less than 2^31 frames ahead. Modular subtraction remains
    // correct across bgfx's uint32 frame wrap; unsigned absolute ordering does not.
    static boolean readbackReady(int current, int ready) {
        return current - ready >= 0;
    }

    /** Exterior object counts of the last drawn frame. */
    BgfxWorldStreamRenderer.FrameCounts frameCounts() {
        requireOpen();
        return renderer.frameCounts();
    }

    /** The World light of the next frames (#373); #177 feeds it from the light tables. */
    void light(WorldLight value) {
        requireOpen();
        renderer.light(value);
    }

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#terrainLighting}. */
    void terrainLighting(boolean enabled) {
        requireOpen();
        renderer.terrainLighting(enabled);
    }

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#objectCulling}. */
    void objectCulling(boolean enabled) {
        requireOpen();
        renderer.objectCulling(enabled);
    }

    Stats stats() {
        requireOpen();
        var stats = bgfx_get_stats();
        int pending = 0;
        for (var slot : readbacks) {
            if (slot.ticket != null) {
                pending++;
            }
        }
        return new Stats(
                renderer.stats(),
                Short.toUnsignedInt(stats.numDynamicVertexBuffers()),
                Short.toUnsignedInt(stats.numVertexBuffers()),
                Short.toUnsignedInt(stats.numTextures()),
                contextBuffers,
                contextTextures,
                contextGpuHeap,
                sequence,
                pending,
                stats.gpuMemoryUsed(),
                samples);
    }

    private void requireOpen() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Wrong native owner");
        }
        if (closed) {
            throw new IllegalStateException("Native World owner closed");
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Native World allocation failed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Wrong native owner");
        }
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (initialized) {
                // Native destination pointers remain owned through every returned readback fence.
                boolean pending;
                do {
                    pending = false;
                    for (var slot : readbacks) {
                        if (slot != null && slot.ticket != null) {
                            pending = true;
                        }
                    }
                    if (pending) {
                        currentFrame = bgfx_frame(BGFX_FRAME_NONE);
                        for (var slot : readbacks) {
                            if (slot != null
                                    && slot.ticket != null
                                    && readbackReady(currentFrame, slot.ready)) {
                                slot.ticket = null;
                            }
                        }
                    }
                } while (pending);
                if (renderer != null) {
                    renderer.close();
                }
                if (target != INVALID) {
                    bgfx_destroy_frame_buffer(target);
                    target = INVALID;
                }
                for (short texture : new short[] {color, depth}) {
                    if (texture != INVALID) {
                        bgfx_destroy_texture(texture);
                    }
                }
                for (var slot : readbacks) {
                    if (slot != null && slot.texture != INVALID) {
                        bgfx_destroy_texture(slot.texture);
                    }
                }
            }
        } finally {
            try {
                if (initialized) {
                    bgfx_shutdown();
                    initialized = false;
                }
            } finally {
                if (renderer != null) {
                    renderer.afterShutdown();
                }
                for (var slot : readbacks) {
                    if (slot != null && slot.pixels != null) {
                        MemoryUtil.memFree(slot.pixels);
                        slot.pixels = null;
                    }
                }
                if (cropped != null) {
                    MemoryUtil.memFree(cropped);
                    cropped = null;
                }
                if (acquired) {
                    CONTEXT.set(false);
                    acquired = false;
                }
            }
        }
    }
}
