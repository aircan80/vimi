package com.vimi.gui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableDoubleValue;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Calls {@code changed} when the render scale of the window showing the World view changes: a
 * move to a monitor with another display scale, or a scale setting change. The stream frame is
 * sized from that scale (WorldFrameLimits), so it must be re-requested. Follows the owner (for
 * example the scene's window) as it is replaced, and never keeps listening to an old one.
 *
 * <p>A monitor move updates the X and then the Y scale. Notifications are coalesced into one
 * posted check of the (x, y) pair, so a move requests exactly one frame, never an intermediate
 * one with the new X and the old Y scale.
 */
final class WorldRenderScaleWatch<T> {
    /** The two scale axes of one owner. */
    record Scales(ObservableDoubleValue x, ObservableDoubleValue y) {}

    private record Pair(double x, double y) {}

    private final Function<T, Scales> scales;
    private final Consumer<Runnable> post;
    private final Runnable changed;
    private final ChangeListener<Number> scaleListener;
    private Scales current;
    private Pair notified;
    private boolean pending;

    /** The World view's real wiring: the node's scene's window, checked on the FX queue. */
    static WorldRenderScaleWatch<Window> forNode(Node node, Runnable changed) {
        return forNode(node, Platform::runLater, changed);
    }

    static WorldRenderScaleWatch<Window> forNode(
            Node node, Consumer<Runnable> post, Runnable changed) {
        return new WorldRenderScaleWatch<>(
                node.sceneProperty().flatMap(Scene::windowProperty),
                window -> new Scales(window.renderScaleXProperty(), window.renderScaleYProperty()),
                post,
                changed);
    }

    WorldRenderScaleWatch(
            ObservableValue<? extends T> owner,
            Function<T, Scales> scales,
            Consumer<Runnable> post,
            Runnable changed) {
        this.scales = Objects.requireNonNull(scales);
        this.post = Objects.requireNonNull(post);
        this.changed = Objects.requireNonNull(changed);
        scaleListener = (o, before, after) -> schedule();
        owner.addListener((o, before, after) -> {
            attach(after);
            schedule();
        });
        attach(owner.getValue());
        notified = pair();
    }

    private void attach(T owner) {
        if (current != null) {
            current.x().removeListener(scaleListener);
            current.y().removeListener(scaleListener);
        }
        current = owner == null ? null : scales.apply(owner);
        if (current != null) {
            current.x().addListener(scaleListener);
            current.y().addListener(scaleListener);
        }
    }

    private Pair pair() {
        return current == null ? null : new Pair(current.x().get(), current.y().get());
    }

    /** One posted check per burst of changes; requests a frame only for a new (x, y) pair. */
    private void schedule() {
        if (pending) return;
        pending = true;
        post.accept(() -> {
            pending = false;
            var now = pair();
            if (now != null && !now.equals(notified)) {
                notified = now;
                changed.run();
            }
        });
    }
}
