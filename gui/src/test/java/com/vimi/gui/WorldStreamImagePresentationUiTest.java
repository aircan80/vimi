package com.vimi.gui;

import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.image.ImageView;

/**
 * PixelBuffer presentation (the default) on the FX thread: two buffers alternate, a rejected
 * frame never touches the visible image, resize reallocates, and pixels arrive exactly. Also
 * times one 2560x1440 present for both modes. Needs the JavaFX toolkit (a desktop session).
 */
public final class WorldStreamImagePresentationUiTest {
    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        var done = new CompletableFuture<String>();
        Platform.runLater(() -> {
            try {
                done.complete(run());
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        System.out.println(done.get(60, TimeUnit.SECONDS));
        Platform.exit();
    }

    private static String run() {
        var view = new ImageView();
        var presentation = new WorldStreamImagePresentation(view, WorldStreamImagePresentation::pixelBufferSurface);
        check(presentation.present(2, 1, pixels(2, 0xffff0000), () -> true, () -> true),
                "First frame publishes");
        var first = view.getImage();
        check(first.getPixelReader().getArgb(1, 0) == 0xffff0000, "PixelBuffer pixels arrive");
        check(presentation.present(2, 1, pixels(2, 0xff00ff00), () -> true, () -> true),
                "Second frame publishes");
        var second = view.getImage();
        check(second != first && second.getPixelReader().getArgb(0, 0) == 0xff00ff00,
                "The back buffer becomes the front");
        check(!presentation.present(2, 1, pixels(2, 0xff0000ff), () -> true, () -> false),
                "Rejected frame");
        check(view.getImage() == second && second.getPixelReader().getArgb(0, 0) == 0xff00ff00,
                "A rejected frame never touches the visible image");
        for (int i = 0; i < 10; i++) {
            presentation.present(2, 1, pixels(2, 0xff000000 | i), () -> true, () -> true);
            check(view.getImage() == first || view.getImage() == second,
                    "Warm frames alternate exactly two buffers");
            check(view.getImage().getPixelReader().getArgb(1, 0) == (0xff000000 | i),
                    "Each frame's own pixels");
        }
        check(presentation.present(3, 2, pixels(6, 0xff123456), () -> true, () -> true),
                "Resized frame publishes");
        check(view.getImage().getWidth() == 3 && view.getImage().getHeight() == 2
                        && view.getImage().getPixelReader().getArgb(2, 1) == 0xff123456,
                "Resize reallocates at the exact size");
        return "WORLD_PRESENTATION_PIXELBUFFER_PASS " + time(false) + " " + time(true);
    }

    /** Mean FX time to present one 2560x1440 frame (copy into the back image only). */
    private static String time(boolean setPixels) {
        int w = 2560, h = 1440;
        var frame = new int[w * h];
        java.util.Arrays.fill(frame, 0xff336699);
        var presentation = new WorldStreamImagePresentation(new ImageView(), setPixels ? WorldStreamImagePresentation::setPixelsSurface : WorldStreamImagePresentation::pixelBufferSurface);
        long total = 0;
        for (int i = 0; i < 80; i++) {
            long start = System.nanoTime();
            presentation.present(w, h, IntBuffer.wrap(frame), () -> true, () -> true);
            if (i >= 20) total += System.nanoTime() - start;
        }
        return String.format(java.util.Locale.ROOT, "%s=%.3fms",
                setPixels ? "setPixels" : "pixelBuffer", total / 1e6 / 60);
    }

    private static IntBuffer pixels(int count, int argb) {
        var values = new int[count];
        java.util.Arrays.fill(values, argb);
        return IntBuffer.wrap(values).asReadOnlyBuffer();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private WorldStreamImagePresentationUiTest() {}
}
