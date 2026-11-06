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
        check(!presentation.present(3, 1,
                IntBuffer.wrap(new int[] {0xffff0000, 0xffff0000, 0xffff0000}),
                () -> true, () -> false), "Resized rejected upload remains non-visible");
        check(image.getImage() == previous, "Resize rejection retains the exact old front image");
        check(presentation.present(3, 1,
                IntBuffer.wrap(new int[] {0xff0000ff, 0xff0000ff, 0xff0000ff}),
                () -> true, () -> true), "Accepted resized image publishes");
        check(image.getImage().getWidth() == 3
                && image.getImage().getPixelReader().getArgb(2, 0) == 0xff0000ff,
                "Resized image has exact accepted dimensions and pixels");
        try {
            presentation.present(Integer.MAX_VALUE, 1, pixels(0), () -> true, () -> true);
            throw new AssertionError("Oversized bitmap must be rejected before allocation");
        } catch (IllegalArgumentException expected) { }
        // The shared cap, not 800x500: a native 2560x1600 frame is accepted, one more is not.
        int max = com.vibeminer.rendering.world.WorldFrameLimits.MAX_WIDTH
                * com.vibeminer.rendering.world.WorldFrameLimits.MAX_HEIGHT;
        var full = new int[max];
        java.util.Arrays.fill(full, 0xff00ff00);
        check(presentation.present(2560, 1600, IntBuffer.wrap(full).asReadOnlyBuffer(),
                () -> true, () -> true), "Native-resolution frame up to the shared cap");
        check(image.getImage().getWidth() == 2560 && image.getImage().getHeight() == 1600,
                "Presented at native size");
        try {
            presentation.present(2561, 1, IntBuffer.wrap(new int[2561]), () -> true, () -> true);
            throw new AssertionError("Width past the shared cap must be rejected");
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
