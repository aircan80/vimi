package com.vimi.gui;

/**
 * Holds a new streamed frame size until it has stayed the same for one whole animation pulse, so
 * dragging a window edge changes the stream's size policy once at the end instead of on every
 * layout step (each change discards in-flight readbacks). Until then the previous size keeps
 * being requested and its frames are scaled to fit. FX thread only.
 */
final class WorldResizeDebounce {
    private int width, height, pendingWidth, pendingHeight;
    private boolean pending, quiet;

    /** The size requested now; 0 x 0 before the first offer. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Forgets every size, e.g. when a new stream starts; its first offer applies at once. */
    void reset() {
        width = height = 0;
        pending = false;
    }

    /**
     * Offers the size the viewport wants. The first size applies at once; a different size
     * waits for {@link #pulse}. Offering the applied size again cancels a pending change.
     */
    void offer(int nextWidth, int nextHeight) {
        if (width == 0 || height == 0) {
            width = nextWidth;
            height = nextHeight;
            pending = false;
            return;
        }
        if (nextWidth == width && nextHeight == height) {
            pending = false;
            return;
        }
        if (!pending || nextWidth != pendingWidth || nextHeight != pendingHeight) {
            pending = true;
            quiet = false;
            pendingWidth = nextWidth;
            pendingHeight = nextHeight;
        }
    }

    /**
     * Once per animation pulse. True when a pending size has been offered unchanged across a
     * whole pulse and is now the requested size; the caller then sends it.
     */
    boolean pulse() {
        if (!pending) return false;
        if (!quiet) {
            quiet = true;
            return false;
        }
        width = pendingWidth;
        height = pendingHeight;
        pending = false;
        return true;
    }
}
