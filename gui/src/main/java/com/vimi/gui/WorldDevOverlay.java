package com.vimi.gui;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.value.ObservableStringValue;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The World explorer's developer overlay: the explorer's long status texts, split into labelled
 * monospaced lines and shown once each, on pages that the overlay key cycles (Stream, Render,
 * Input, Audio, then off). The text panel is mouse-transparent; Retry GPU, Retry textures and Copy
 * report are its only clickable elements. The Retry buttons fire the view's own buttons and follow
 * their disabled state; Copy report puts every page, redacted (no local paths or account names),
 * on the clipboard. The buttons never take focus, keep their events from reaching the viewport's
 * flight handlers, and hand focus back to the viewport after a click. FX-owned.
 */
final class WorldDevOverlay extends VBox {
    /** Where the texts come from; each changing text redraws the shown page. */
    record Sources(ObservableStringValue stream, ObservableStringValue gpu,
            ObservableStringValue renderer, ObservableStringValue position,
            ObservableStringValue look, ObservableStringValue motion, Supplier<String> held,
            ObservableStringValue keys, ObservableStringValue audio) {
        Sources {
            Objects.requireNonNull(stream);
            Objects.requireNonNull(gpu);
            Objects.requireNonNull(renderer);
            Objects.requireNonNull(position);
            Objects.requireNonNull(look);
            Objects.requireNonNull(motion);
            Objects.requireNonNull(held);
            Objects.requireNonNull(keys);
            Objects.requireNonNull(audio);
        }

        WorldDevReport.Texts read() {
            return new WorldDevReport.Texts(stream.get(), gpu.get(), renderer.get(),
                    position.get(), look.get(), motion.get(), held.get(), keys.get(), audio.get());
        }
    }

    private final Sources sources;
    private final Runnable refocus;
    private final Label header = new Label(), body = new Label();
    private final Button retryGpu = new Button("Retry GPU"),
            retryTextures = new Button("Retry textures"),
            copyReport = new Button("Copy report");
    private Consumer<String> clipboard = WorldDevOverlay::systemClipboard;
    private WorldDevReport.Page page;
    private String key = "F3";

    /**
     * @param gpuRetry the view's Retry GPU button; the overlay's copy fires it and follows its
     *     disabled state
     * @param textureRetry the view's Retry textures button, likewise
     * @param refocus gives keyboard focus back to the viewport after a click
     */
    WorldDevOverlay(Sources sources, Button gpuRetry, Button textureRetry, Runnable refocus) {
        this.sources = Objects.requireNonNull(sources);
        this.refocus = Objects.requireNonNull(refocus);
        setId("world-dev-overlay");
        setSpacing(4);
        setAlignment(Pos.TOP_LEFT);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        // Only the buttons take the mouse; the rest of the panel lets clicks reach the world.
        setPickOnBounds(false);
        setFocusTraversable(false);
        String text = "-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: #e6eef0;";
        header.setId("world-dev-overlay-header");
        header.setStyle(text + " -fx-font-weight: bold;");
        body.setId("world-dev-overlay-text");
        body.setStyle(text);
        var panel = new VBox(6, header, body);
        panel.setId("world-dev-overlay-panel");
        panel.setMouseTransparent(true);
        panel.setStyle("-fx-background-color: rgba(8,12,16,0.78); -fx-background-radius: 6;"
                + " -fx-padding: 8 10 8 10;");
        retryGpu.setId("world-dev-overlay-retry-gpu");
        retryTextures.setId("world-dev-overlay-retry-textures");
        copyReport.setId("world-dev-overlay-copy");
        mirror(retryGpu, gpuRetry);
        mirror(retryTextures, textureRetry);
        action(copyReport, () -> clipboard.accept(WorldDevReport.report(sources.read().map(WorldDiagnostics::clean))));
        copyReport.setAccessibleText("Copy every developer overlay page as text");
        var actions = new HBox(6, retryGpu, retryTextures, copyReport);
        actions.setId("world-dev-overlay-actions");
        actions.setPickOnBounds(false);
        getChildren().addAll(panel, actions);
        for (var source : List.of(sources.stream(), sources.gpu(), sources.renderer(),
                sources.position(), sources.look(), sources.motion(), sources.keys(),
                sources.audio())) {
            source.addListener((o, a, b) -> refresh());
        }
        show(null);
    }

    private void mirror(Button copy, Button target) {
        copy.disableProperty().bind(target.disableProperty());
        copy.setAccessibleText(target.getText());
        action(copy, target::fire);
    }

    /** The minimap's pattern: handle first, then stop the event, then return focus. */
    private void action(Button button, Runnable run) {
        button.setFocusTraversable(false);
        button.setStyle("-fx-font-size: 11px;");
        button.setOnAction(event -> {
            run.run();
            refocus.run();
        });
        button.addEventHandler(KeyEvent.ANY, KeyEvent::consume);
        button.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        button.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
    }

    /** Advances off, Stream, Render, Input, Audio, off. */
    void cycle() {
        show(page == null ? WorldDevReport.Page.STREAM : page.next());
    }

    /** Shows {@code next}, or hides the overlay for null. */
    void show(WorldDevReport.Page next) {
        page = next;
        setVisible(next != null);
        refresh();
    }

    /** The page shown, or null while off. */
    WorldDevReport.Page page() {
        return page;
    }

    /** The key name the header names as the next-page key (follows rebinding). */
    void key(String name) {
        key = name == null || name.isBlank() ? "unbound" : name;
        refresh();
    }

    /** The shown page's text; for tests. */
    String text() {
        return body.getText();
    }

    String headerText() {
        return header.getText();
    }

    /** Replaces the system clipboard; for tests. */
    void clipboard(Consumer<String> next) {
        clipboard = Objects.requireNonNull(next);
    }

    /**
     * Redraws the shown page from the current texts; a no-op while off. The view calls it on key
     * presses and releases, so the held keys stay current.
     */
    void refresh() {
        if (page == null) return;
        String top = WorldDevReport.header(page, key),
                text = WorldDevReport.format(WorldDevReport.lines(page, sources.read()));
        if (!top.equals(header.getText())) header.setText(top);
        if (!text.equals(body.getText())) body.setText(text);
        setAccessibleText("Developer overlay, " + page.label + " page");
    }

    private static void systemClipboard(String text) {
        var content = new ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }
}
