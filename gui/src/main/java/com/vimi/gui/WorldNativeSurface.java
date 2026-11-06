package com.vimi.gui;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * #381 (opt-in, {@code -Dvimi.world.nativePresent=true}, Windows): the World view's side of native
 * presentation. The helper presents into a child window of the application window, which JavaFX
 * cannot draw over; so the viewport node (its overlays, minimap and input handlers) moves onto an
 * owned, transparent stage laid exactly over an empty placeholder in the layout, and the child is
 * placed under it, in the parent's client area in physical pixels. The placeholder keeps the
 * layout's space. {@link #detach} returns the viewport to the layout; the view then uses readback.
 */
final class WorldNativeSurface {
    /** Nearly transparent, so the overlay stage still receives every mouse event over the view. */
    static final String HIT_TEST_BACKGROUND = "-fx-background-color: rgba(0,0,0,0.004);";
    /** How long the platform may keep refusing to place the child before the view gives up. */
    static final long REFUSAL_NANOS = 1_000_000_000L;

    /** What one pulse found. */
    enum Pulse {
        /** Not attached, or the view is not shown: the child and the overlay are hidden. */
        HIDDEN,
        /** The child is over the placeholder and shown. */
        PLACED,
        /** No child yet (the helper is starting), or a refusal shorter than the limit. */
        PENDING,
        /** The platform refused the placement for {@link #REFUSAL_NANOS}: use readback. */
        FAILED
    }

    private final Region viewport;
    private final Node frameImage;
    private final Consumer<Node> restore;
    private final WorldNativePresenter presenter;
    private final Region placeholder = new Region();
    private Stage overlay;
    private String viewportStyle;
    private long refusedSince = -1;

    /**
     * {@code restore} puts the viewport back where the placeholder is; {@code frameImage} is the
     * readback image, hidden while the native child shows the frames.
     */
    WorldNativeSurface(Region viewport, Node frameImage, Consumer<Node> restore,
            WorldNativePresenter presenter) {
        this.viewport = Objects.requireNonNull(viewport);
        this.frameImage = Objects.requireNonNull(frameImage);
        this.restore = Objects.requireNonNull(restore);
        this.presenter = Objects.requireNonNull(presenter);
        placeholder.setId("world-native-placeholder");
        placeholder.setMinSize(0, 0);
    }

    /** The layout node that holds the view's space while native presentation is attached. */
    Region placeholder() {
        return placeholder;
    }

    boolean attached() {
        return overlay != null;
    }

    /** The application window's native handle (the helper's parent), or 0 when unavailable. */
    static long parentHandle(Window window) {
        return window == null ? 0 : WorldNativePresenter.windowHandle(window).orElse(0L);
    }

    /**
     * Moves the viewport onto the overlay stage owned by {@code owner}; the caller puts the
     * placeholder where the viewport was. Idempotent.
     */
    void attach(Window owner) {
        if (overlay != null) return;
        long parent = parentHandle(owner);
        presenter.parent(parent);
        var root = new StackPane();
        root.setStyle("-fx-background-color: transparent;");
        var stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) stage.initOwner(owner);
        var scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        if (owner != null && owner.getScene() != null)
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        stage.setScene(scene);
        overlay = stage;
        viewportStyle = viewport.getStyle();
        viewport.setStyle(HIT_TEST_BACKGROUND);
        frameImage.setVisible(false);
        root.getChildren().add(viewport);
    }

    /**
     * Once per pulse at {@code now} (nanoseconds): keeps the child and the overlay over the
     * placeholder, or hides both while the view is not shown. The child is created hidden and is
     * shown here only once placed.
     */
    Pulse pulse(boolean visible, long now) {
        if (overlay == null) return Pulse.HIDDEN;
        Bounds screen = placeholder.localToScreen(placeholder.getLayoutBounds());
        Bounds scene = placeholder.localToScene(placeholder.getLayoutBounds());
        var window = placeholder.getScene() == null ? null : placeholder.getScene().getWindow();
        if (!visible || screen == null || scene == null || window == null
                || scene.getWidth() < 1 || scene.getHeight() < 1) {
            presenter.hide();
            if (overlay.isShowing()) overlay.hide();
            refusedSince = -1;
            return Pulse.HIDDEN;
        }
        var placement = WorldNativePresenter.Placement.of(scene.getMinX(), scene.getMinY(),
                scene.getWidth(), scene.getHeight(), window.getOutputScaleX(), window.getOutputScaleY());
        var shown = presenter.show(placement);
        if (overlay.getX() != screen.getMinX()) overlay.setX(screen.getMinX());
        if (overlay.getY() != screen.getMinY()) overlay.setY(screen.getMinY());
        if (overlay.getWidth() != screen.getWidth()) overlay.setWidth(screen.getWidth());
        if (overlay.getHeight() != screen.getHeight()) overlay.setHeight(screen.getHeight());
        if (!overlay.isShowing()) overlay.show();
        if (shown != WorldNativePresenter.Result.REFUSED) {
            refusedSince = -1;
            return shown == WorldNativePresenter.Result.SHOWN ? Pulse.PLACED : Pulse.PENDING;
        }
        if (refusedSince < 0) refusedSince = now;
        return now - refusedSince >= REFUSAL_NANOS ? Pulse.FAILED : Pulse.PENDING;
    }

    /**
     * Gives the viewport the keyboard. While attached it lives on the overlay stage, which must be
     * the active window for its focus owner to receive keys, so that stage is activated too.
     */
    void focus() {
        viewport.requestFocus();
        if (overlay != null && overlay.isShowing()) overlay.requestFocus();
    }

    /** Returns the viewport to the layout, shows the readback image again; idempotent. */
    void detach() {
        var stage = overlay;
        if (stage == null) return;
        overlay = null;
        refusedSince = -1;
        presenter.hide();
        if (stage.getScene().getRoot() instanceof StackPane root) root.getChildren().remove(viewport);
        viewport.setStyle(viewportStyle);
        frameImage.setVisible(true);
        restore.accept(viewport);
        stage.close();
    }

    /** The overlay stage while attached; for tests. */
    Stage overlay() {
        return overlay;
    }
}
