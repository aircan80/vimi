package com.vimi.gui;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Render-scale changes re-request World frames once per (x, y) change; a replaced window is never
 * listened to again. No toolkit: posting is a manual queue standing in for the FX queue.
 */
public final class WorldRenderScaleWatchTest {
    private record Window(SimpleDoubleProperty x, SimpleDoubleProperty y) {
        Window(double scale) {
            this(new SimpleDoubleProperty(scale), new SimpleDoubleProperty(scale));
        }
    }

    public static void main(String[] args) {
        var first = new Window(1);
        var owner = new SimpleObjectProperty<Window>(first);
        var queue = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        new WorldRenderScaleWatch<Window>(
                owner, w -> new WorldRenderScaleWatch.Scales(w.x(), w.y()), queue::add,
                calls::incrementAndGet);
        drain(queue);
        check(calls.get() == 0, "Installing does not request a frame by itself");

        // A monitor move: X then Y change in the same FX turn. Exactly one request, after both.
        first.x().set(1.5);
        first.y().set(1.5);
        check(queue.size() == 1, "Both axis changes coalesce into one posted check");
        drain(queue);
        check(calls.get() == 1, "A monitor move requests exactly one frame");

        first.x().set(1.25);
        drain(queue);
        check(calls.get() == 2, "A single axis change is still seen");
        first.x().set(1.5);
        first.x().set(1.25);
        drain(queue);
        check(calls.get() == 2, "A change and its undo in one turn request nothing");

        // A new window at another scale: one request; at the same scale: none.
        var second = new Window(2);
        owner.set(second);
        drain(queue);
        check(calls.get() == 3, "A new window at another scale re-requests once");
        var sameScale = new Window(2);
        owner.set(sameScale);
        drain(queue);
        check(calls.get() == 3, "A new window at the same scale needs no new frame");
        second.x().set(3);
        drain(queue);
        check(calls.get() == 3, "A replaced window is no longer watched");
        sameScale.y().set(1);
        drain(queue);
        check(calls.get() == 4, "The current window is watched");

        owner.set(null);
        drain(queue);
        check(calls.get() == 4, "No window: nothing to size for");
        sameScale.x().set(4);
        drain(queue);
        check(calls.get() == 4, "Detached from the last window");
        System.out.println("WORLD_RENDER_SCALE_WATCH_PASS");
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        Runnable task;
        while ((task = queue.poll()) != null) task.run();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private WorldRenderScaleWatchTest() {}
}
