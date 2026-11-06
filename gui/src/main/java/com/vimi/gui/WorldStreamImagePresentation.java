package com.vimi.gui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * FX-owned two-image presentation: rejected work never modifies the visible bitmap.
 *
 * <p>Each image is backed by a {@link PixelBuffer} over a direct buffer: a frame is one copy into
 * the back buffer, then the image swap, instead of {@code setPixels} (about half the FX time at
 * 2560x1440, #370). {@code -Dvimi.world.setPixels=true} keeps the old {@code setPixels} path for
 * comparison.
 */
final class WorldStreamImagePresentation {
    static final String SET_PIXELS_PROPERTY = "vimi.world.setPixels";

    /** One presentable image and, in buffer mode, the pixels it shows. */
    private record Surface(WritableImage image, PixelBuffer<IntBuffer> buffer) {
        int width() {
            return (int) image.getWidth();
        }

        int height() {
            return (int) image.getHeight();
        }
    }

    private final ImageView view;
    private final boolean setPixels;
    private Surface front, back;

    WorldStreamImagePresentation(ImageView view) {
        this(view, Boolean.getBoolean(SET_PIXELS_PROPERTY));
    }

    WorldStreamImagePresentation(ImageView view, boolean setPixels) {
        this.view = Objects.requireNonNull(view);
        this.setPixels = setPixels;
    }

    boolean present(int width, int height, IntBuffer pixels,
            BooleanSupplier currentView, BooleanSupplier accepted) {
        Objects.requireNonNull(pixels);
        Objects.requireNonNull(currentView);
        Objects.requireNonNull(accepted);
        if (!com.vibeminer.rendering.world.WorldFrameLimits.fits(width, height)
                || pixels.remaining() != width * height) {
            throw new IllegalArgumentException("World presentation pixel bounds");
        }
        if (back == null || back.width() != width || back.height() != height) {
            // Release the obsolete non-visible owner before replacement allocation at resize.
            back = null;
            back = surface(width, height);
        }
        if (back.buffer() == null) {
            back.image().getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbPreInstance(), pixels, width);
        } else {
            // FX thread, inside updateBuffer: the PixelBuffer contract for writing its pixels.
            back.buffer().updateBuffer(buffer -> {
                var ints = buffer.getBuffer();
                ints.clear();
                ints.put(pixels.duplicate());
                ints.flip();
                return null; // the whole image changed
            });
        }
        if (!currentView.getAsBoolean() || !accepted.getAsBoolean()) return false;
        var published = back;
        // The previous front becomes the next back only if it is still what the view shows.
        var shown = view.getImage();
        var previous = front != null && shown == front.image() ? front
                // setPixels mode may also reuse a plain image another path left in the view.
                : setPixels && shown instanceof WritableImage old && old != published.image()
                        ? new Surface(old, null) : null;
        view.setImage(published.image());
        front = published;
        back = previous != null && previous.width() == width && previous.height() == height
                ? previous : null;
        return true;
    }

    private Surface surface(int width, int height) {
        if (setPixels) return new Surface(new WritableImage(width, height), null);
        var ints = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        var buffer = new PixelBuffer<>(width, height, ints, PixelFormat.getIntArgbPreInstance());
        return new Surface(new WritableImage(buffer), buffer);
    }

    void clear() {
        back = null;
        front = null;
    }
}
