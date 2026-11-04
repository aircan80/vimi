package com.vimi.gui;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;

/** Detached bitmap ownership test; no live window, source capability or GPU is manufactured. */
public final class WorldStreamImagePresentationTest {
    public static void main(String[] args) {
        var image = new ImageView();
        var original = new WritableImage(2, 1);
        original.getPixelWriter().setArgb(0, 0, 0xff0000ff);
        original.getPixelWriter().setArgb(1, 0, 0xff0000ff);
        image.setImage(original);
        var presentation = new WorldStreamImagePresentation(image);
        check(!presentation.present(2, 1, pixels(0xffff0000), () -> true, () -> false),
                "Rejected final acceptance cannot publish pixels");
        check(image.getImage() == original && original.getPixelReader().getArgb(0, 0) == 0xff0000ff,
                "Even a reused-size visible bitmap remains untouched on rejection");
        var accepts = new AtomicInteger();
        check(!presentation.present(2, 1, pixels(0xffff0000), () -> false,
                () -> { accepts.incrementAndGet(); return true; }),
                "Expired view cannot reach acceptance");
        check(accepts.get() == 0 && image.getImage() == original, "No stale-view swap");
        check(presentation.present(2, 1, pixels(0xffff0000), () -> true, () -> true),
                "Current accepted pixels publish");
        var second = image.getImage();
        check(second != original && second.getPixelReader().getArgb(1, 0) == 0xffff0000,
                "Accepted upload uses the staged bitmap");
        for (int i = 0; i < 20; i++) {
            check(presentation.present(2, 1, pixels(0xff00ff00), () -> true, () -> true),
                    "Warm presentation accepted");
            check(image.getImage() == original || image.getImage() == second,
                    "Warm frames reuse exactly two bitmaps");
        }
        var previous = image.getImage();
        try {
            presentation.present(2, 1, pixels(0xffff0000), () -> true,
                    () -> { throw new IllegalStateException("Authored acceptance failure"); });
            throw new AssertionError("Acceptance failure must propagate");
        } catch (IllegalStateException expected) {
            check(image.getImage() == previous
                    && previous.getPixelReader().getArgb(0, 0) == 0xff00ff00,
                    "Throwing acceptance cannot modify the visible image");
        }
        try {
            presentation.present(Integer.MAX_VALUE, 1, pixels(0), () -> true, () -> true);
            throw new AssertionError("Oversized bitmap must be rejected before allocation");
        } catch (IllegalArgumentException expected) { }
        presentation.clear();
        System.out.println("World staged image publication: rejection, expiry and bounded bitmap reuse passed");
    }
    private static IntBuffer pixels(int color) {
        return IntBuffer.wrap(new int[] {color, color}).asReadOnlyBuffer();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
