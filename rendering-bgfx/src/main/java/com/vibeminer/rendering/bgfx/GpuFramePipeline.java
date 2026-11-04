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
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;

import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Bounded offscreen render/readback ring. All driver waits and mmap publication stay in the child.
 */
final class GpuFramePipeline implements AutoCloseable {
    private static final short INVALID = (short) 0xffff;

    private static final class Slot {
        short texture = INVALID;
        final ByteBuffer pixels;
        GpuFrameRequest view;
        int ready;

        Slot(int capacity) {
            pixels = MemoryUtil.memAlloc(capacity);
        }
    }

    private final GpuFrameFile frames;
    private final BgfxModelRenderer renderer;
    private final BgfxTerrainRenderer terrainRenderer;
    private final Set<Integer> qualified;
    private final Slot[] slots = new Slot[3];
    private final ByteBuffer flipped;
    private short color = INVALID, depth = INVALID, target = INVALID;
    private int width, height, currentFrame;
    private long sequence, publishedRevision;
    private boolean closed;

    GpuFramePipeline(GpuModelScene scene, Set<Integer> qualified, GpuFrameFile frames)
            throws IOException {
        this(scene, qualified, null, null, frames, () -> false);
    }

    GpuFramePipeline(TerrainScene scene, GpuFrameFile frames, BooleanSupplier canceled)
            throws IOException {
        this(null, Set.of(), scene, null, frames, canceled);
    }

    GpuFramePipeline(
            TerrainScene scene,
            GpuTerrainTextureAnnex.Decoded textures,
            GpuFrameFile frames,
            BooleanSupplier canceled)
            throws IOException {
        this(null, Set.of(), scene, textures, frames, canceled);
    }

    TerrainTextureStatus textureStatus(TerrainTextureStatus.Code inputFailure) {
        return terrainRenderer.textureStatus(inputFailure);
    }

    private GpuFramePipeline(
            GpuModelScene model,
            Set<Integer> qualified,
            TerrainScene terrain,
            GpuTerrainTextureAnnex.Decoded textures,
            GpuFrameFile frames,
            BooleanSupplier canceled)
            throws IOException {
        this.frames = frames;
        this.qualified = Set.copyOf(qualified);
        if (terrain != null
                && (frames.maxWidth() > BgfxTerrainRenderer.MAX_WIDTH
                        || frames.maxHeight() > BgfxTerrainRenderer.MAX_HEIGHT)) {
            throw new IOException("Terrain frame allocation exceeds800x500 budget");
        }
        var caps = bgfx_get_caps();
        if ((caps.supported() & (BGFX_CAPS_TEXTURE_READ_BACK | BGFX_CAPS_TEXTURE_BLIT))
                        != (BGFX_CAPS_TEXTURE_READ_BACK | BGFX_CAPS_TEXTURE_BLIT)
                || !bgfx_is_texture_valid(0, false, 1, BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_RT)
                || !bgfx_is_texture_valid(
                        0,
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_BGRA8,
                        BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK)
                || !bgfx_is_texture_valid(0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT)) {
            throw new IOException("GPU backend does not support the inline frame bridge");
        }
        renderer = model == null ? null : new BgfxModelRenderer(model);
        terrainRenderer =
                terrain == null ? null : new BgfxTerrainRenderer(terrain, textures, canceled);
        int capacity =
                Math.multiplyExact(Math.multiplyExact(frames.maxWidth(), frames.maxHeight()), 4);
        ByteBuffer flip = null;
        try {
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new Slot(capacity);
            }
            flip = MemoryUtil.memAlloc(capacity);
        } catch (RuntimeException | Error failure) {
            for (var slot : slots) {
                if (slot != null) {
                    MemoryUtil.memFree(slot.pixels);
                }
            }
            closeRenderer();
            throw failure;
        }
        flipped = flip;
    }

    boolean submit(GpuFrameRequest view) throws IOException {
        if (closed) {
            throw new IllegalStateException("Inline GPU pipeline closed");
        }
        if (terrainRenderer != null
                ? !(view instanceof GpuWorldRequest)
                : !(view instanceof GpuViewRequest)) {
            throw new IOException("Control kind differs from the renderer scene");
        }
        if (view.width() > frames.maxWidth() || view.height() > frames.maxHeight()) {
            throw new IOException("Requested GPU frame exceeds the shared bridge");
        }
        if (width != view.width() || height != view.height()) {
            if (pending()) {
                return false;
            }
            resize(view.width(), view.height());
        }
        Slot available = null;
        for (var slot : slots) {
            if (slot.view == null) {
                available = slot;
                break;
            }
        }
        if (available == null) {
            return false;
        }
        if (view instanceof GpuWorldRequest world) {
            terrainRenderer.render(world.camera(), width, height);
        } else {
            var model = (GpuViewRequest) view;
            renderer.draw(
                    width,
                    height,
                    model.camera(qualified),
                    model.textures(),
                    model.qualifiedMaterialsOnly());
        }
        bgfx_blit(1, available.texture, 0, 0, 0, 0, color, 0, 0, 0, 0, width, height, 1);
        available.pixels.clear().limit(width * height * 4);
        // Destination and texture remain owned by this slot through the returned ready frame.
        available.view = view;
        available.ready = bgfx_read_texture(available.texture, available.pixels, 0, 0);
        return true;
    }

    boolean pending() {
        for (var slot : slots) {
            if (slot.view != null) {
                return true;
            }
        }
        return false;
    }

    boolean advance() throws IOException {
        if (!pending()) {
            return false;
        }
        currentFrame = bgfx_frame(BGFX_FRAME_NONE);
        Slot newest = null;
        for (var slot : slots) {
            if (slot.view != null
                    && Integer.compareUnsigned(currentFrame, slot.ready) >= 0
                    && (newest == null || slot.view.revision() > newest.view.revision())) {
                newest = slot;
            }
        }
        boolean published = false;
        if (newest != null && newest.view.revision() > publishedRevision) {
            var view = newest.view;
            ByteBuffer pixels =
                    newest.pixels.duplicate().position(0).limit(view.width() * view.height() * 4);
            if (bgfx_get_caps().originBottomLeft()) {
                flipped.clear();
                int stride = view.width() * 4;
                for (int row = view.height() - 1; row >= 0; row--) {
                    var source =
                            pixels.duplicate().position(row * stride).limit((row + 1) * stride);
                    flipped.put(source);
                }
                flipped.flip();
                pixels = flipped;
            }
            frames.write(++sequence, view.revision(), view.width(), view.height(), pixels);
            publishedRevision = view.revision();
            published = true;
        }
        for (var slot : slots) {
            if (slot.view != null && Integer.compareUnsigned(currentFrame, slot.ready) >= 0) {
                slot.view = null;
            }
        }
        return published;
    }

    private void resize(int nextWidth, int nextHeight) throws IOException {
        destroyTargets();
        width = nextWidth;
        height = nextHeight;
        color =
                bgfx_create_texture_2d(
                        width,
                        height,
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_BGRA8,
                        BGFX_TEXTURE_RT,
                        null,
                        0);
        depth =
                bgfx_create_texture_2d(
                        width,
                        height,
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_D32F,
                        BGFX_TEXTURE_RT,
                        null,
                        0);
        if (color == INVALID || depth == INVALID) {
            throw new IOException("GPU render target unavailable");
        }
        target = bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false);
        if (target == INVALID) {
            throw new IOException("GPU frame buffer unavailable");
        }
        for (var slot : slots) {
            slot.texture =
                    bgfx_create_texture_2d(
                            width,
                            height,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_BGRA8,
                            BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                            null,
                            0);
            if (slot.texture == INVALID) {
                throw new IOException("GPU frame readback unavailable");
            }
        }
        bgfx_set_view_frame_buffer(0, target);
    }

    private void destroyTargets() {
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
        for (var slot : slots) {
            if (slot.texture != INVALID) {
                bgfx_destroy_texture(slot.texture);
                slot.texture = INVALID;
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // bgfx retains readback destination pointers until their ready frame, even on cancellation.
        while (pending()) {
            currentFrame = bgfx_frame(BGFX_FRAME_NONE);
            for (var slot : slots) {
                if (slot.view != null && Integer.compareUnsigned(currentFrame, slot.ready) >= 0) {
                    slot.view = null;
                }
            }
        }
        destroyTargets();
        closeRenderer();
        for (var slot : slots) {
            MemoryUtil.memFree(slot.pixels);
        }
        MemoryUtil.memFree(flipped);
    }

    private void closeRenderer() {
        if (renderer != null) {
            renderer.close();
        }
        if (terrainRenderer != null) {
            terrainRenderer.close();
        }
    }
}
