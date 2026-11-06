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
        // setPixels mode: needs no JavaFX toolkit. The PixelBuffer mode (the default) is
        // covered with the toolkit by WorldStreamImagePresentationUiTest.
        var presentation = new WorldStreamImagePresentation(image, WorldStreamImagePresentation::setPixelsSurface);
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
        var shown = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<javafx.scene.image.Image, Boolean>());
        for (int i = 0; i < 20; i++) {
            check(presentation.present(2, 1, pixels(0xff00ff00), () -> true, () -> true),
                    "Warm presentation accepted");
            shown.add(image.getImage());
            check(image.getImage() != original,
                    "An image another path left in the view is never written into");
        }
        check(shown.size() == 2, "Warm frames reuse exactly two bitmaps: " + shown.size());
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
        surfaceRulesHoldForAnyFactory();
        System.out.println("World staged image publication: rejection, expiry and bounded bitmap reuse passed");
    }
    /** A surface that records writes and fails if it is ever written while visible. */
    private static final class Recording implements WorldStreamImagePresentation.Surface {
        final javafx.scene.image.WritableImage image;
        final ImageView view;
        int writes;

        Recording(ImageView view, int width, int height) {
            this.view = view;
            image = new javafx.scene.image.WritableImage(width, height);
        }

        public javafx.scene.image.Image image() { return image; }

        public int width() { return (int) image.getWidth(); }

        public int height() { return (int) image.getHeight(); }

        public void write(IntBuffer pixels) {
            check(view.getImage() != image, "A surface is never written while it is shown");
            writes++;
        }
    }

    /**
     * The front/back rules with an injected factory, exactly as the default PixelBuffer surfaces
     * use them, without a toolkit: two surfaces alternate, the shown one is never written, the
     * front is reused only while still shown, and a resize drops both wrong-size surfaces.
     */
    private static void surfaceRulesHoldForAnyFactory() {
        var view = new ImageView();
        var made = new java.util.ArrayList<Recording>();
        var presentation = new WorldStreamImagePresentation(view, (w, h) -> {
            var surface = new Recording(view, w, h);
            made.add(surface);
            return surface;
        });
        for (int i = 0; i < 10; i++) {
            check(presentation.present(2, 1, pixels(0xff000000 | i), () -> true, () -> true),
                    "accepted");
        }
        check(made.size() == 2, "Two surfaces alternate: " + made.size());
        check(made.get(0).writes == 5 && made.get(1).writes == 5, "Writes alternate evenly");
        // Rejection: the back is written, the visible surface is untouched.
        var visible = view.getImage();
        check(!presentation.present(2, 1, pixels(0xff0000aa), () -> true, () -> false), "rejected");
        check(view.getImage() == visible, "Rejection keeps the visible surface");
        // Another path replaces the view's image: the old front is no longer reused as back.
        view.setImage(new javafx.scene.image.WritableImage(2, 1));
        presentation.present(2, 1, pixels(0xff0000bb), () -> true, () -> true);
        presentation.present(2, 1, pixels(0xff0000cc), () -> true, () -> true);
        check(made.size() == 3, "A front no longer shown is not reused; one new surface");
        // Something points the view at the presentation's current back surface: it must be
        // replaced, never written while visible (Recording fails the test if it were).
        Recording back;
        try {
            var field = WorldStreamImagePresentation.class.getDeclaredField("back");
            field.setAccessible(true);
            back = (Recording) field.get(presentation);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        check(back != null && back.image != view.getImage(), "A back surface is kept");
        view.setImage(back.image);
        int count = made.size();
        presentation.present(2, 1, pixels(0xff0000dd), () -> true, () -> true);
        check(made.size() == count + 1, "A shown back is replaced, not written");
        // Resize: both wrong-size surfaces are dropped; new ones are made at the new size.
        int before = made.size();
        for (int i = 0; i < 3; i++)
            presentation.present(3, 2, IntBuffer.wrap(new int[6]), () -> true, () -> true);
        check(made.size() == before + 2, "Resize makes exactly two new surfaces: " + made.size());
        for (var surface : made.subList(before, made.size()))
            check(surface.width() == 3 && surface.height() == 2, "At the new size");
        check(view.getImage().getWidth() == 3, "Showing the new size");
    }

    private static IntBuffer pixels(int color) {
        return IntBuffer.wrap(new int[] {color, color}).asReadOnlyBuffer();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
