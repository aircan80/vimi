package com.vimi.gui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * FX-owned two-image presentation: rejected work never modifies the visible bitmap.
 *
 * <p>Each image is backed by a {@link PixelBuffer} over a direct buffer: a frame is one copy into
 * the back buffer, then the image swap, instead of {@code setPixels} (about half the FX copy time
 * at 2560x1440, #370). {@code -Dvimi.world.setPixels=true} keeps the old {@code setPixels} path
 * for comparison. The surfaces come from a {@link Surfaces} factory, so the front/back rules are
 * tested without a JavaFX toolkit.
 */
final class WorldStreamImagePresentation {
    static final String SET_PIXELS_PROPERTY = "vimi.world.setPixels";

    /** One presentable image of a fixed size and the way to write a whole frame into it. */
    interface Surface {
        Image image();

        int width();

        int height();

        /** Writes {@code width * height} pixels; only ever called on a non-visible surface. */
        void write(IntBuffer pixels);
    }

    /** Makes a surface of exactly {@code width x height}. */
    interface Surfaces {
        Surface create(int width, int height);
    }

    private final ImageView view;
    private final Surfaces surfaces;
    private Surface front, back;

    WorldStreamImagePresentation(ImageView view) {
        this(view, Boolean.getBoolean(SET_PIXELS_PROPERTY)
                ? WorldStreamImagePresentation::setPixelsSurface
                : WorldStreamImagePresentation::pixelBufferSurface);
    }

    WorldStreamImagePresentation(ImageView view, Surfaces surfaces) {
        this.view = Objects.requireNonNull(view);
        this.surfaces = Objects.requireNonNull(surfaces);
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
        if (back == null || back.width() != width || back.height() != height
                || back.image() == view.getImage()) {
            // Release the obsolete non-visible owner before replacement allocation at resize.
            // Never write into what the view shows, whatever happened to the view meanwhile.
            back = null;
            back = surfaces.create(width, height);
        }
        back.write(pixels.duplicate());
        if (!currentView.getAsBoolean() || !accepted.getAsBoolean()) return false;
        var published = back;
        // The previous front becomes the next back only if it is still what the view shows and
        // has the new size; an image another path put in the view is never written into.
        var previous = front != null && view.getImage() == front.image()
                && front.width() == width && front.height() == height ? front : null;
        view.setImage(published.image());
        front = published;
        back = previous;
        return true;
    }

    void clear() {
        back = null;
        front = null;
    }

    /** Default: a PixelBuffer over a direct native-order buffer, written inside updateBuffer. */
    static Surface pixelBufferSurface(int width, int height) {
        var ints = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        var buffer = new PixelBuffer<>(width, height, ints, PixelFormat.getIntArgbPreInstance());
        var image = new WritableImage(buffer);
        return new Surface() {
            public Image image() { return image; }

            public int width() { return width; }

            public int height() { return height; }

            public void write(IntBuffer pixels) {
                // FX thread, inside updateBuffer: the PixelBuffer contract for writing its pixels.
                buffer.updateBuffer(updated -> {
                    var target = updated.getBuffer();
                    target.clear();
                    target.put(pixels);
                    target.flip();
                    return null; // the whole image changed
                });
            }
        };
    }

    /** -Dvimi.world.setPixels=true: a plain WritableImage written with setPixels. */
    static Surface setPixelsSurface(int width, int height) {
        var image = new WritableImage(width, height);
        return new Surface() {
            public Image image() { return image; }

            public int width() { return width; }

            public int height() { return height; }

            public void write(IntBuffer pixels) {
                image.getPixelWriter().setPixels(0, 0, width, height,
                        PixelFormat.getIntArgbPreInstance(), pixels, width);
            }
        };
    }
}
