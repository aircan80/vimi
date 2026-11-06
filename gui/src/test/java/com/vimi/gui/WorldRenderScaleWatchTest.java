package com.vimi.gui;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

/** Render-scale changes re-request World frames; a replaced window is never listened to again. */
public final class WorldRenderScaleWatchTest {
    private record Window(SimpleDoubleProperty x, SimpleDoubleProperty y) {
        Window(double scale) {
            this(new SimpleDoubleProperty(scale), new SimpleDoubleProperty(scale));
        }
    }

    public static void main(String[] args) {
        var first = new Window(1);
        var owner = new SimpleObjectProperty<Window>(first);
        var calls = new AtomicInteger();
        new WorldRenderScaleWatch<Window>(
                owner, w -> new WorldRenderScaleWatch.Scales(w.x(), w.y()), calls::incrementAndGet);
        check(calls.get() == 0, "Installing does not request a frame by itself");
        first.x().set(1.5);
        check(calls.get() == 1, "Horizontal scale change (monitor move) re-requests a frame");
        first.y().set(1.25);
        check(calls.get() == 2, "Each axis is watched");
        first.x().set(1.5);
        check(calls.get() == 2, "An unchanged value requests nothing");

        var second = new Window(2);
        owner.set(second);
        check(calls.get() == 3, "A new window re-requests a frame at its scale");
        first.x().set(3);
        check(calls.get() == 3, "The replaced window is no longer watched");
        second.y().set(1);
        check(calls.get() == 4, "The new window is watched");

        owner.set(null);
        check(calls.get() == 4, "No window: nothing to size for");
        second.x().set(4);
        check(calls.get() == 4, "Detached from the last window");
        System.out.println("WORLD_RENDER_SCALE_WATCH_PASS");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private WorldRenderScaleWatchTest() {}
}
