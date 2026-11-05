package com.vimi.gui;

import com.vibeminer.concepts.world.WorldBookmarks;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bookmark search/list and typed-coordinate travel. Store work completes on its own worker and is
 * applied on FX only while this panel's store generation is current.
 */
final class WorldTravelPanel extends VBox {
    private final TextField query = new TextField();
    private final ListView<WorldBookmarkStore.Entry> list = new ListView<>();
    private final Button go = new Button("Go to bookmark");
    private final Button delete = new Button("Delete");
    private final TextField coordinates = new TextField();
    private final Button travel = new Button("Go to coordinates");
    private final Label status = new Label();
    private final Consumer<WorldBookmarks.Bookmark> openBookmark;
    private final Consumer<double[]> openCoordinates;
    private WorldBookmarkStore store;
    private boolean ownedStore;
    private long generation;
    private List<WorldBookmarkStore.Entry> entries = List.of();
    private int unreadable;

    WorldTravelPanel(Consumer<WorldBookmarks.Bookmark> openBookmark, Consumer<double[]> openCoordinates) {
        super(8);
        this.openBookmark = Objects.requireNonNull(openBookmark);
        this.openCoordinates = Objects.requireNonNull(openCoordinates);
        setId("world-travel");
        query.setId("world-bookmark-search");
        query.setPromptText("Search bookmarks by name, map or map ID");
        list.setId("world-bookmarks");
        list.setPrefHeight(130);
        list.setPlaceholder(new Label("No saved bookmarks. Save one from the explorer."));
        go.setId("world-bookmark-go");
        delete.setId("world-bookmark-delete");
        coordinates.setId("world-travel-coordinates");
        coordinates.setPromptText("Native x y [z] on the selected map");
        travel.setId("world-travel-go");
        status.setId("world-travel-status");
        status.setWrapText(true);
        query.textProperty().addListener((o, a, b) -> refresh());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> buttons());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) go.fire();
        });
        go.setOnAction(e -> {
            var selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) openBookmark.accept(selected.bookmark());
        });
        delete.setOnAction(e -> {
            var selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) apply(store.remove(selected.id()), "Bookmark deleted");
        });
        travel.setOnAction(e -> {
            var parsed = WorldBookmarks.parseCoordinates(coordinates.getText());
            if (parsed.isEmpty()) status.setText("Enter native coordinates as x y or x y z.");
            else openCoordinates.accept(parsed.get());
        });
        coordinates.setOnAction(e -> travel.fire());
        getChildren().setAll(
                new Label("BOOKMARKS & TRAVEL · saved cameras and native coordinates"),
                query, list, new HBox(8, go, delete),
                new HBox(8, coordinates, travel), status);
        use(WorldBookmarkStore.session(), true);
    }

    /** Replaces the session store with an application-owned store; this panel never closes it. */
    void store(WorldBookmarkStore shared) {
        if (shared != null && shared != store) use(shared, false);
    }

    private void use(WorldBookmarkStore next, boolean owned) {
        if (store != null && ownedStore) store.close();
        store = next;
        ownedStore = owned;
        generation++;
        entries = List.of();
        unreadable = 0;
        refresh();
        apply(store.load(), null);
    }

    void save(WorldBookmarks.Bookmark bookmark) {
        apply(store.add(bookmark), "Saved bookmark " + bookmark.name());
    }

    void status(String message) {
        status.setText(message);
    }

    String status() {
        return status.getText();
    }

    void close() {
        generation++;
        if (ownedStore) store.close();
    }

    private void apply(java.util.concurrent.CompletableFuture<WorldBookmarkStore.Snapshot> work,
            String done) {
        long expected = generation;
        work.whenComplete((snapshot, failure) -> javafx.application.Platform.runLater(() -> {
            if (expected != generation) return;
            if (failure != null) {
                status.setText("Bookmarks unavailable: " + rootMessage(failure));
                return;
            }
            entries = snapshot.entries();
            unreadable = snapshot.unreadable();
            refresh();
            String scope = snapshot.persistent() ? "" : " Bookmarks last for this session only.";
            String broken = unreadable == 0 ? ""
                    : " " + unreadable + " saved bookmark(s) are unreadable and were left unchanged.";
            status.setText((done == null ? entries.size() + " bookmark(s)." : done + ".") + scope + broken);
        }));
    }

    private void refresh() {
        var matching = WorldBookmarks.search(entries.stream().map(WorldBookmarkStore.Entry::bookmark)
                .toList(), query.getText());
        var selected = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(entries.stream()
                .filter(entry -> matching.contains(entry.bookmark())).toList());
        if (selected != null && list.getItems().contains(selected)) list.getSelectionModel().select(selected);
        buttons();
    }

    private void buttons() {
        boolean none = list.getSelectionModel().getSelectedItem() == null;
        go.setDisable(none);
        delete.setDisable(none);
    }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
