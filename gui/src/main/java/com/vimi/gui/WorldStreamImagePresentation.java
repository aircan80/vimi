package com.vimi.gui;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** FX-owned two-image presentation: rejected work never modifies the visible bitmap. */
final class WorldStreamImagePresentation {
    private final ImageView view;
    private WritableImage back;

    WorldStreamImagePresentation(ImageView view) {
        this.view = Objects.requireNonNull(view);
    }

    boolean present(int width, int height, IntBuffer pixels,
            BooleanSupplier currentView, BooleanSupplier accepted) {
        Objects.requireNonNull(pixels);
        Objects.requireNonNull(currentView);
        Objects.requireNonNull(accepted);
        if (width < 1 || width > 800 || height < 1 || height > 500
                || pixels.remaining() != width * height) {
            throw new IllegalArgumentException("World presentation pixel bounds");
        }
        if (back == null || back.getWidth() != width || back.getHeight() != height) {
            // Release the obsolete non-visible owner before replacement allocation at resize.
            back = null;
            back = new WritableImage(width, height);
        }
        back.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbPreInstance(), pixels, width);
        if (!currentView.getAsBoolean() || !accepted.getAsBoolean()) return false;
        var previous = view.getImage();
        var published = back;
        view.setImage(published);
        back = previous instanceof WritableImage old
                && old != published && old.getWidth() == width && old.getHeight() == height
                ? old : null;
        return true;
    }

    void clear() { back = null; }
}
