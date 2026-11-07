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

import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.WorldFrameLimits;
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

    /**
     * Diagnostic: {@code -Dvimi.world.readbackStaging=true} keeps the old path (readback into a
     * private buffer, crop copy, then a copy into the frame mapping) for comparison. By default
     * each readback writes straight into its own mapping slot and is published in place.
     */
    static final String STAGING_PROPERTY = "vimi.world.readbackStaging";

    private final boolean staging = Boolean.getBoolean(STAGING_PROPERTY);
    // Time spent publishing frames (the copies this path removes); diagnostics and proofs.
    private long publishNanos, publishedFrames;
    // Direct readback: the slot holding the newest publication, never reused while it is newest.
    private int publishedIndex = -1;
    // Per frame size: advance() wall time on calls that publish (bgfx_frame, which now copies
    // the readback into the mapping, plus publication), and how many. Diagnostics and proofs.
    private final java.util.LinkedHashMap<String, long[]> advanceTimings =
            new java.util.LinkedHashMap<>();

    /** Targets grow in these steps, so a window drag reallocates only every few hundred px. */
    static final int TARGET_STEP = 256;

    private static final AtomicBoolean CONTEXT = new AtomicBoolean();

    private static final class Readback {
        int index;
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
            int msaaSamples,
            // The WDL horizon (#372), last so earlier fields keep their positions.
            BgfxWorldStreamRenderer.HorizonCounts horizon) {}

    /** Reset flags: no back buffer; anisotropic samplers use the device maximum (issue #357). */
    static final int RESET_FLAGS = org.lwjgl.bgfx.BGFX.BGFX_RESET_MAXANISOTROPY;

    /**
     * The transient vertex budget (#382): bgfx's default, raised when needed to hold a whole frame
     * of object instance data at the caps ({@link
     * BgfxWorldStreamRenderer#MAX_FRAME_INSTANCE_BYTES}) plus 1 MiB for anything else that uses
     * transient vertices.
     */
    static int transientVertexBytes(int bgfxDefault) {
        return transientVertexBytes(bgfxDefault, BgfxWorldStreamRenderer.MAX_FRAME_INSTANCE_BYTES);
    }

    static int transientVertexBytes(int bgfxDefault, int frameInstanceBytes) {
        return Math.max(bgfxDefault, Math.addExact(frameInstanceBytes, 1 << 20));
    }

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
    // Opt-in measurement only: the previous publication, 0 while timings are off.
    private long lastPublished;
    private boolean renderingSubmitted;
    private int width, height, currentFrame, contextBuffers, contextTextures, samples = 1;
    // The render target, readback textures and buffers, sized to the viewport (see targetSize).
    private int targetWidth, targetHeight, reallocations, failedWidth, failedHeight;
    private long contextGpuHeap;
    private boolean initialized, acquired, closed, renderedThisFrame;
    // #381 spike: non-null only with --present-parent (native presentation into a child window).
    private final WorldWindowPresentation window;

    WorldStreamNativeOwner(
            WorldStreamEnvelope.Session session, WorldFrameFileV2 frames, int backend)
            throws IOException {
        this(session, frames, backend, null);
    }

    /** #381 spike: with {@code window}, frames are presented into it instead of read back. */
    WorldStreamNativeOwner(
            WorldStreamEnvelope.Session session,
            WorldFrameFileV2 frames,
            int backend,
            WorldWindowPresentation window)
            throws IOException {
        this.window = window;
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
                // Room for a frame of object instance data at the caps (#382), on top of bgfx's
                // default transient budget, whichever way the context presents.
                init.limits()
                        .maxTransientVbSize(
                                transientVertexBytes(init.limits().maxTransientVbSize()));
                // Offscreen context only; one-frame command latency is explicitly qualified by
                // proof.
                if (window != null) {
                    window.configure(init); // #381
                } else {
                    init.resolution()
                            .width(0)
                            .height(0)
                            .reset(RESET_FLAGS)
                            .maxFrameLatency((byte) 1);
                }
                BGFXPlatform.bgfx_render_frame(0);
                boolean started = bgfx_init(init);
                if (started && window != null && !WorldWindowPresentation.depthSupported()) {
                    // #381: the terrain renderer needs a D32F target; say so plainly.
                    bgfx_shutdown();
                    throw new IOException(
                            "Native World presentation needs a D32F depth target on this GPU");
                }
                if (!started) {
                    throw new IOException("Headless World backend unavailable");
                }
                initialized = true;
            }
            var caps = bgfx_get_caps();
            if (caps.originBottomLeft()) {
                // World backends are D3D11, Vulkan and Metal (top-left origin); the readback crop
                // takes the first rows of the target, which a bottom-left origin (GL) would not.
                throw new IOException("World requires a top-left-origin backend");
            }
            GpuViewerMain.requireHardware(
                    Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
            // Diagnostics only (stderr, never the protocol): the renderer bgfx actually created,
            // so proofs can check that the requested backend was used (#379).
            System.err.println(
                    "World v2 renderer: "
                            + org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name(caps.rendererType()));
            System.err.flush();
            // Readback capabilities matter only without a window (#381 spike).
            if (window == null
                    && ((caps.supported() & (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK))
                                    != (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK)
                            || !bgfx_is_texture_valid(
                                    0, false, 1, BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_RT)
                            || !bgfx_is_texture_valid(
                                    0,
                                    false,
                                    1,
                                    BGFX_TEXTURE_FORMAT_BGRA8,
                                    BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK))) {
                throw new IOException("World readback capabilities unavailable");
            }
            bgfx_frame(BGFX_FRAME_NONE);
            bgfx_frame(BGFX_FRAME_NONE);
            contextBuffers = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
            contextTextures = Short.toUnsignedInt(bgfx_get_stats().numTextures());
            contextGpuHeap = bgfx_get_stats().gpuMemoryUsed();
            renderer = new BgfxWorldStreamRenderer(session);
            if (window != null) {
                // #381: view 0 draws to the child window's backbuffer, single-sampled; no readback.
                return;
            }
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
            for (int i = 0; i < readbacks.length; i++) {
                readbacks[i] = new Readback();
                // Direct readback: readback i writes mapping slot i (both have three).
                readbacks[i].index = i;
            }
            // The render target is allocated by the first camera, at its size.
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * The allocation for a {@code width x height} frame: rounded up to {@link #TARGET_STEP}, within
     * the shared cap. Reallocate when the frame outgrows it, or shrinks below half of it in either
     * dimension (to give the memory back), never on a small change.
     */
    static int stepped(int frame, int max) {
        return Math.min(max, (frame + TARGET_STEP - 1) / TARGET_STEP * TARGET_STEP);
    }

    /**
     * The allocation {@code {width, height}} for a frame: unchanged while the frame fits and the
     * allocation is at most twice the stepped frame's area (bgfx reads back the whole readback
     * texture, so this bounds the over-read to 2x); else the stepped frame size.
     */
    static int[] targetSize(
            int frameWidth, int frameHeight, int allocatedWidth, int allocatedHeight) {
        int width = stepped(frameWidth, WorldFrameLimits.MAX_WIDTH);
        int height = stepped(frameHeight, WorldFrameLimits.MAX_HEIGHT);
        boolean fits = frameWidth <= allocatedWidth && frameHeight <= allocatedHeight;
        if (fits && (long) allocatedWidth * allocatedHeight <= 2L * width * height) {
            return new int[] {allocatedWidth, allocatedHeight};
        }
        return new int[] {width, height};
    }

    /** Sizes to try when allocating: the stepped size, then the exact frame if that differs. */
    static java.util.List<int[]> allocationAttempts(
            int[] stepped, int frameWidth, int frameHeight) {
        return stepped[0] == frameWidth && stepped[1] == frameHeight
                ? java.util.List.of(stepped)
                : java.util.List.of(stepped, new int[] {frameWidth, frameHeight});
    }

    /** Frees the target; callers ensure no readback still writes into a slot's buffer. */
    private void releaseTarget() {
        if (target != INVALID) {
            bgfx_destroy_frame_buffer(target);
            target = INVALID;
        }
        if (color != INVALID) {
            bgfx_destroy_texture(color);
            color = INVALID;
        }
        if (depth != INVALID) {
            bgfx_destroy_texture(depth);
            depth = INVALID;
        }
        for (var slot : readbacks) {
            if (slot.texture != INVALID) {
                bgfx_destroy_texture(slot.texture);
                slot.texture = INVALID;
            }
            if (slot.pixels != null) {
                if (staging) {
                    MemoryUtil.memFree(slot.pixels);
                } // else: a view of the frame mapping, owned by the mapping
                slot.pixels = null;
            }
        }
        if (cropped != null) {
            MemoryUtil.memFree(cropped);
            cropped = null;
        }
        targetWidth = 0;
        targetHeight = 0;
    }

    /**
     * Allocates the stepped target, then the exact frame size if that fails. If neither fits in
     * device memory this size is not drawn (the session stays up; a smaller viewport retries).
     */
    private boolean allocateWithFallback(int[] stepped) {
        RuntimeException last = null;
        for (int[] size : allocationAttempts(stepped, width, height)) {
            try {
                allocateTarget(size[0], size[1]);
                return true;
            } catch (IOException | OutOfMemoryError failure) {
                releaseTarget();
                last = new IllegalStateException(size[0] + "x" + size[1], failure);
            }
        }
        if (failedWidth != width || failedHeight != height) {
            failedWidth = width;
            failedHeight = height;
            System.err.println(
                    "World render target unavailable for "
                            + width
                            + "x"
                            + height
                            + ": "
                            + last.getCause());
            System.err.flush();
        }
        return false;
    }

    private void allocateTarget(int newWidth, int newHeight) throws IOException {
        releaseTarget();
        int bytes = newWidth * newHeight * 4;
        color =
                valid(
                        bgfx_create_texture_2d(
                                newWidth,
                                newHeight,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_BGRA8,
                                colourFlags(samples),
                                null,
                                0));
        depth =
                valid(
                        bgfx_create_texture_2d(
                                newWidth,
                                newHeight,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_D32F,
                                depthFlags(samples),
                                null,
                                0));
        target = valid(bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false));
        for (var slot : readbacks) {
            slot.pixels = staging ? MemoryUtil.memAlloc(bytes) : null;
            slot.texture =
                    valid(
                            bgfx_create_texture_2d(
                                    newWidth,
                                    newHeight,
                                    false,
                                    1,
                                    BGFX_TEXTURE_FORMAT_BGRA8,
                                    BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                                    null,
                                    0));
        }
        cropped = staging ? MemoryUtil.memAlloc(bytes) : null;
        bgfx_set_view_frame_buffer(0, target);
        targetWidth = newWidth;
        targetHeight = newHeight;
        reallocations++;
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

    /** The compressed object texture formats the renderer samples as blocks (bit per code). */
    int objectFormatMask() {
        requireOpen();
        return renderer.objectFormatMask();
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
        if (window != null) {
            return submitWindow(command);
        }
        if (submittedCamera == cameraRevision
                && submittedContent == renderer.contentRevision()
                && submittedObjects == renderer.objectsRevision()
                && submittedPolicy == policy
                && !renderer.horizonWaiting()) {
            return false;
        }
        if (renderedThisFrame) {
            return false;
        }
        int[] want = targetSize(width, height, targetWidth, targetHeight);
        if (want[0] != targetWidth || want[1] != targetHeight) {
            for (var slot : readbacks) {
                if (slot.ticket != null) {
                    return false; // advance() retires in-flight readbacks first
                }
            }
            if (!allocateWithFallback(want)) {
                return false;
            }
        }
        Readback available = null;
        for (var slot : readbacks) {
            // Least recently used free slot. Direct readback never reuses the slot holding the
            // newest publication: with two readbacks in flight that would mark the parent's only
            // fresh frame in progress before it could read it, so the draw waits a bgfx frame
            // for an in-flight readback to retire instead.
            if (slot.ticket == null
                    && (staging || slot.index != publishedIndex)
                    && (available == null || slot.order < available.order)) {
                available = slot;
            }
        }
        if (available == null) {
            return false;
        }
        if (order == WorldFrameFileV2.MAX_SEQUENCE) {
            throw new IllegalStateException("Frame order exhausted");
        }
        // The frame's sky travels with its camera, so a coalesced camera never loses it.
        // TODO(#479): with the WDL horizon drawn, pass its end here (WorldStreamHorizon.FOG_END)
        // instead of NaN and drop draw()'s withHorizon, so a recorded fog end below the horizon
        // is kept; whichever of #479 and #486 lands second makes this one-line change.
        renderer.fog(WorldFog.sky(command.sky(), Float.NaN));
        long drawing = WorldStageTimings.start();
        var ticket = renderer.draw(command.camera(), cameraRevision, policy, width, height);
        bgfx_blit(1, available.texture, 0, 0, 0, 0, color, 0, 0, 0, 0, width, height, 1);
        if (!staging) {
            try {
                available.pixels =
                        frames.beginDirect(available.index, targetWidth * targetHeight * 4);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        available.pixels.clear();
        available.ticket = ticket;
        available.order = ++order;
        available.ready = bgfx_read_texture(available.texture, available.pixels, 0, 0);
        if (drawing != 0L) {
            WorldStageTimings.stop("child.frame.submitCpu", drawing);
            cullCounts(renderer.frameCounts());
        }
        renderedThisFrame = true;
        submittedCamera = cameraRevision;
        submittedContent = ticket.content();
        submittedObjects = renderer.objectsRevision();
        submittedPolicy = policy;
        return true;
    }

    /** #381 spike: every presented frame draws the latest camera at the window's size. */
    private boolean submitWindow(WorldStreamControl.Camera command) {
        if (renderedThisFrame) {
            return false;
        }
        int[] size = window.frameSize();
        if (size == null) {
            return false; // No area (minimised, hidden): nothing to draw into.
        }
        renderer.drawWindow(command.camera(), cameraRevision, policy, size[0], size[1]);
        window.drew();
        renderedThisFrame = true;
        submittedCamera = cameraRevision;
        submittedContent = renderer.contentRevision();
        submittedObjects = renderer.objectsRevision();
        submittedPolicy = policy;
        return true;
    }

    /** #381: camera revision of the latest scene frame presented into the window; 0 before. */
    long presentedCamera() {
        return presentedCamera;
    }

    private long presentedCamera, presentedContent;

    /** #381: content revision of the latest scene frame presented into the window. */
    long presentedContent() {
        return presentedContent;
    }

    /** True in native presentation: frames go to the child window, never to the mapping. */
    boolean presentsToWindow() {
        return window != null;
    }

    boolean advance() throws IOException {
        requireOpen();
        if (window != null) {
            // #381 spike: present (waits for the display), no readback or mapping write.
            if (!renderedThisFrame) {
                window.clearUndrawn();
            }
            // A covered or minimised window presents without waiting: pace presents instead.
            long pace = window.paceNanos(System.nanoTime());
            if (pace > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(pace);
            }
            boolean drew = renderedThisFrame;
            currentFrame = bgfx_frame(BGFX_FRAME_NONE);
            renderedThisFrame = false;
            renderer.pollUploads();
            window.presented(System.nanoTime());
            if (drew) {
                presentedCamera = submittedCamera;
                presentedContent = submittedContent;
            }
            return false;
        }
        long advanceStart = System.nanoTime();
        // Single-threaded bgfx renders the previously submitted frame inside bgfx_frame, so the
        // call after a submission is the one that renders it and reports its statistics.
        boolean rendering = renderingSubmitted;
        renderingSubmitted = renderedThisFrame;
        long framing = WorldStageTimings.start();
        currentFrame = bgfx_frame(BGFX_FRAME_NONE);
        if (framing != 0L) {
            WorldStageTimings.stop(
                    rendering ? "child.frame.bgfxFrameRender" : "child.frame.bgfxFrameIdle",
                    framing);
            if (rendering) {
                frameStats();
            }
        }
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
            long start = System.nanoTime();
            var frame =
                    new WorldStreamFrameGate.Frame(
                            session.nonce(),
                            ++sequence,
                            ticket.camera(),
                            ticket.content(),
                            ticket.policy(),
                            ticket.width(),
                            ticket.height());
            long writing = WorldStageTimings.start();
            if (staging) {
                cropped.clear();
                int stride = targetWidth * 4, length = ticket.width() * 4;
                for (int row = 0; row < ticket.height(); row++) {
                    int sourceRow = row; // top-left origin (checked at init)
                    cropped.put(
                            newest.pixels
                                    .duplicate()
                                    .position(sourceRow * stride)
                                    .limit(sourceRow * stride + length));
                }
                cropped.flip();
                frames.write(frame, cropped);
            } else {
                // The readback already wrote the rows into the slot: publish the header only.
                frames.publishDirect(newest.index, frame, targetWidth * 4);
            }
            if (writing != 0L) {
                WorldStageTimings.stop("child.frame.cropAndPublish", writing);
                long now = System.nanoTime();
                if (lastPublished != 0L) {
                    WorldStageTimings.record("child.frame.publishInterval", now - lastPublished);
                }
                lastPublished = now;
            }
            long end = System.nanoTime();
            publishNanos += end - start;
            publishedFrames++;
            if (!staging) {
                publishedIndex = newest.index;
            }
            var timing =
                    advanceTimings.computeIfAbsent(
                            ticket.width() + "x" + ticket.height(), size -> new long[2]);
            timing[0] += end - advanceStart;
            timing[1]++;
            publishedCamera = ticket.camera();
            publishedContent = ticket.content();
            published = true;
        }
        for (var slot : readbacks) {
            if (slot.ticket != null && readbackReady(currentFrame, slot.ready)) {
                if (!staging && slot != newest) {
                    // A superseded or stale readback: its slot holds no frame to publish.
                    frames.abandonDirect(slot.index);
                }
                slot.ticket = null;
            }
        }
        return published;
    }

    /**
     * What the CPU cull kept and skipped in the frame just submitted (opt-in only), so a change in
     * {@code child.frame.draws} can be told apart from a change in what was resident: exterior
     * placements tested and culled, object submits, terrain chunk runs tested and culled, terrain
     * submits, object instances drawn and dropped (#382), and portal walks with the interior meshes
     * they culled (#383).
     */
    static void cullCounts(BgfxWorldStreamRenderer.FrameCounts counts) {
        WorldStageTimings.value("child.frame.placements", counts.placements());
        WorldStageTimings.value("child.frame.placementsCulled", counts.culled());
        WorldStageTimings.value("child.frame.objectSubmits", counts.objectSubmits());
        WorldStageTimings.value("child.frame.terrainChunks", counts.chunks());
        WorldStageTimings.value("child.frame.terrainChunksCulled", counts.chunksCulled());
        WorldStageTimings.value("child.frame.terrainSubmits", counts.terrainSubmits());
        WorldStageTimings.value("child.frame.instances", counts.instances());
        WorldStageTimings.value("child.frame.instancesDropped", counts.instancesDropped());
        WorldStageTimings.value("child.frame.portalWalks", counts.portalWalks());
        WorldStageTimings.value("child.frame.portalMeshesCulled", counts.portalMeshesCulled());
    }

    /** bgfx's statistics of the frame just rendered, after a frame that drew (opt-in only). */
    private static void frameStats() {
        var stats = bgfx_get_stats();
        WorldStageTimings.value("child.frame.draws", Integer.toUnsignedLong(stats.numDraw()));
        WorldStageTimings.value("child.frame.blits", Integer.toUnsignedLong(stats.numBlit()));
        // Topology 0 and 1 are triangle lists and strips.
        WorldStageTimings.value(
                "child.frame.triangles",
                Integer.toUnsignedLong(stats.numPrims(0))
                        + Integer.toUnsignedLong(stats.numPrims(1)));
        long cpuFrequency = stats.cpuTimerFreq(), gpuFrequency = stats.gpuTimerFreq();
        if (cpuFrequency > 0) {
            WorldStageTimings.record(
                    "child.frame.bgfxCpuFrame",
                    stats.cpuTimeFrame() * 1_000_000_000L / cpuFrequency);
            WorldStageTimings.record(
                    "child.frame.bgfxCpuRender",
                    (stats.cpuTimeEnd() - stats.cpuTimeBegin()) * 1_000_000_000L / cpuFrequency);
            WorldStageTimings.record(
                    "child.frame.bgfxWaitRender",
                    stats.waitRender() * 1_000_000_000L / cpuFrequency);
        }
        if (gpuFrequency > 0 && stats.gpuTimeEnd() > stats.gpuTimeBegin()) {
            WorldStageTimings.record(
                    "child.frame.bgfxGpu",
                    (stats.gpuTimeEnd() - stats.gpuTimeBegin()) * 1_000_000_000L / gpuFrequency);
        }
        WorldStageTimings.value("child.gpu.textureMemoryMiB", stats.textureMemoryUsed() >> 20);
        if (stats.gpuMemoryUsed() > 0) {
            WorldStageTimings.value("child.gpu.memoryUsedMiB", stats.gpuMemoryUsed() >> 20);
        }
    }

    /** Mean time to publish one frame, in nanoseconds; 0 before the first. Proofs. */
    long meanPublishNanos() {
        return publishedFrames == 0 ? 0 : publishNanos / publishedFrames;
    }

    /** "WxH:meanAdvanceMicros/frames" per frame size, for frames advance() published. */
    String advanceSummary() {
        var out = new StringBuilder();
        advanceTimings.forEach(
                (size, timing) ->
                        out.append(out.isEmpty() ? "" : " ")
                                .append(size)
                                .append(':')
                                .append(timing[0] / timing[1] / 1000)
                                .append("us/")
                                .append(timing[1]));
        return out.toString();
    }

    boolean stagingReadback() {
        return staging;
    }

    /** Current render-target allocation and how often it was (re)made; proofs and tests. */
    int targetWidth() {
        return targetWidth;
    }

    int targetHeight() {
        return targetHeight;
    }

    int reallocations() {
        return reallocations;
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

    /** Draws the map's WDL horizon from now on (#372); its rebuilds run on their own worker. */
    void attachHorizon(com.vibeminer.formats.world.WorldLowRes.LowRes lowRes) throws IOException {
        requireOpen();
        var built = new WorldStreamHorizon(lowRes);
        try {
            renderer.horizon(built);
        } catch (IOException | RuntimeException failed) {
            built.close();
            throw failed;
        }
    }

    /** Whether the next horizon build is waiting for idle upload steps. */
    boolean horizonUploading() {
        requireOpen();
        return renderer.horizonUploading();
    }

    /** One bounded horizon upload step; like late objects, only in idle iterations. */
    void stepHorizon() {
        requireOpen();
        if (!renderedThisFrame) {
            renderer.stepHorizon();
        }
    }

    BgfxWorldStreamRenderer.HorizonCounts horizonCounts() {
        requireOpen();
        return renderer.horizonCounts();
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

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#vertexColours}. */
    void vertexColours(boolean enabled) {
        requireOpen();
        renderer.vertexColours(enabled);
    }

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#objectCulling}. */
    void objectCulling(boolean enabled) {
        requireOpen();
        renderer.objectCulling(enabled);
    }

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#objectInstancing}. */
    void objectInstancing(boolean enabled) {
        requireOpen();
        renderer.objectInstancing(enabled);
    }

    /** Proof seam only: see {@link BgfxWorldStreamRenderer#instanceLimit}. */
    void instanceLimit(int limit) {
        requireOpen();
        renderer.instanceLimit(limit);
    }

    Stats stats() {
        requireOpen();
        var stats = bgfx_get_stats();
        int pending = 0;
        for (var slot : readbacks) {
            if (slot != null && slot.ticket != null) {
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
                samples,
                renderer.horizonCounts());
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
                if (readbacks[readbacks.length - 1] != null) {
                    releaseTarget();
                }
            }
        } finally {
            try {
                if (initialized) {
                    bgfx_shutdown();
                    initialized = false;
                }
                if (window != null) {
                    window.afterShutdown();
                    window.close();
                }
            } finally {
                if (renderer != null) {
                    renderer.afterShutdown();
                }
                for (var slot : readbacks) {
                    if (slot != null && slot.pixels != null) {
                        if (staging) {
                            MemoryUtil.memFree(slot.pixels);
                        } // else: a view of the frame mapping, owned by the mapping
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
