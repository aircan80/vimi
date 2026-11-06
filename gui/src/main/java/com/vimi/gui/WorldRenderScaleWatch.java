package com.vimi.gui;

import java.util.Objects;
import java.util.function.Function;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableDoubleValue;
import javafx.beans.value.ObservableValue;

/**
 * Calls {@code changed} when the render scale of the window showing the World view changes: a
 * move to a monitor with another display scale, or a scale setting change. The stream frame is
 * sized from that scale (WorldFrameLimits), so it must be re-requested. Follows the owner (for
 * example the scene's window) as it is replaced, and never keeps listening to an old one.
 */
final class WorldRenderScaleWatch<T> {
    /** The two scale axes of one owner. */
    record Scales(ObservableDoubleValue x, ObservableDoubleValue y) {}

    private final Function<T, Scales> scales;
    private final Runnable changed;
    private final ChangeListener<Number> scaleListener;
    private Scales current;

    WorldRenderScaleWatch(
            ObservableValue<? extends T> owner, Function<T, Scales> scales, Runnable changed) {
        this.scales = Objects.requireNonNull(scales);
        this.changed = Objects.requireNonNull(changed);
        scaleListener = (o, before, after) -> {
            if (before == null || before.doubleValue() != after.doubleValue()) this.changed.run();
        };
        owner.addListener((o, before, after) -> attach(after, true));
        attach(owner.getValue(), false);
    }

    private void attach(T owner, boolean ownerChanged) {
        if (current != null) {
            current.x().removeListener(scaleListener);
            current.y().removeListener(scaleListener);
        }
        current = owner == null ? null : scales.apply(owner);
        if (current != null) {
            current.x().addListener(scaleListener);
            current.y().addListener(scaleListener);
        }
        // A new window may have another scale than the frame was sized for.
        if (ownerChanged && current != null) changed.run();
    }
}
