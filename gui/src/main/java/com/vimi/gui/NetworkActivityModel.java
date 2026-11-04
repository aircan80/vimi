package com.vimi.gui;

import com.vibeminer.activity.NetworkActivity;
import com.vibeminer.activity.TransferRate;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.stage.Stage;
import javafx.util.Duration;

/** One stage-owned sampler; acquisition threads only update the independent meter. */
final class NetworkActivityModel implements AutoCloseable {
    record Display(
            NetworkActivity.Snapshot snapshot,
            String status,
            String rate,
            String total,
            boolean animate,
            boolean visible) {}

    private final NetworkActivity meter;
    private final Stage stage;
    private final javafx.beans.property.ReadOnlyBooleanProperty reducedMotion;
    private final TransferRate rate = new TransferRate();
    private final ReadOnlyObjectWrapper<Display> display = new ReadOnlyObjectWrapper<>();
    private final Timeline clock =
            new Timeline(new KeyFrame(Duration.millis(250), event -> sample()));
    private final InvalidationListener visibility = ignored -> visibilityChanged();
    private final InvalidationListener motion = ignored -> sample();
    private boolean closed;

    NetworkActivityModel(NetworkActivity meter, Stage stage) {
        this(meter, stage, Platform.getPreferences().reducedMotionProperty());
    }

    NetworkActivityModel(
            NetworkActivity meter,
            Stage stage,
            javafx.beans.property.ReadOnlyBooleanProperty reducedMotion) {
        this.meter = meter;
        this.stage = stage;
        this.reducedMotion = reducedMotion;
        clock.setCycleCount(Timeline.INDEFINITE);
        stage.showingProperty().addListener(visibility);
        stage.iconifiedProperty().addListener(visibility);
        reducedMotion.addListener(motion);
        visibilityChanged();
    }

    ReadOnlyObjectProperty<Display> displayProperty() {
        return display.getReadOnlyProperty();
    }

    Display display() {
        return display.get();
    }

    boolean sampling() {
        return clock.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    private boolean visible() {
        return !closed && stage.isShowing() && !stage.isIconified();
    }

    private void visibilityChanged() {
        clock.stop();
        rate.reset();
        sample();
        if (visible()) clock.play();
    }

    private void sample() {
        if (closed) return;
        var snapshot = meter.snapshot();
        var speed = visible() ? rate.sample(snapshot) : java.util.OptionalDouble.empty();
        String status =
                snapshot.active() == 0
                        ? "Network idle"
                        : snapshot.waitingBody() > 0
                                ? (speed.isPresent() && speed.getAsDouble() > 0
                                        ? "Receiving"
                                        : "Waiting for data")
                                : snapshot.waitingResponse() > 0
                                        ? "Waiting for network"
                                        : "Transfer open";
        String speedText =
                snapshot.active() == 0
                        ? ""
                        : speed.isEmpty() ? "Measuring…" : bytes(speed.getAsDouble()) + "/s";
        var next =
                new Display(
                        snapshot,
                        status,
                        speedText,
                        bytes(snapshot.receivedBytes()) + " received",
                        visible() && snapshot.active() > 0 && !reducedMotion.get(),
                        visible());
        var before = display.get();
        if (before == null
                || !before.status().equals(next.status())
                || !before.rate().equals(next.rate())
                || !before.total().equals(next.total())
                || before.animate() != next.animate()
                || before.visible() != next.visible()
                || !before.snapshot().categories().equals(snapshot.categories())
                || before.snapshot().waitingResponse() != snapshot.waitingResponse()
                || before.snapshot().waitingBody() != snapshot.waitingBody()
                || before.snapshot().handoff() != snapshot.handoff()) display.set(next);
    }

    static String bytes(double value) {
        if (value < 1024) return String.format(java.util.Locale.ROOT, "%.0f B", value);
        if (value < 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f KiB", value / 1024);
        if (value < 1024 * 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f MiB", value / (1024 * 1024));
        return String.format(java.util.Locale.ROOT, "%.1f GiB", value / (1024 * 1024 * 1024));
    }

    @Override
    public void close() {
        if (closed) return;
        clock.stop();
        var before = display.get();
        if (before != null)
            display.set(
                    new Display(
                            before.snapshot(),
                            before.status(),
                            before.rate(),
                            before.total(),
                            false,
                            false));
        closed = true;
        stage.showingProperty().removeListener(visibility);
        stage.iconifiedProperty().removeListener(visibility);
        reducedMotion.removeListener(motion);
        rate.reset();
    }
}
