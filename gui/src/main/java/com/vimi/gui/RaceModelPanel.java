package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceMediaTypes;
import com.vibeminer.concepts.races.RaceTypes;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Selected race/model/class media requests; owns no build or domain handle. */
final class RaceModelPanel extends VBox implements AutoCloseable {
    private final BuildRaces handle;
    private final Supplier<BuildRaces> handles;
    private final LongSupplier revisions;
    private final long revision, raceId;
    private final ComboBox<RaceTypes.PlayerClass> classes = new ComboBox<>();
    private final ComboBox<RaceMediaTypes.Variant> variants = new ComboBox<>();
    private final Label state = new Label("Loading model variants…");
    private final ModelPreviewView viewer = new ModelPreviewView();
    private final VBox evidence = new VBox(7);
    private final RaceVoicePanel voices;
    private Cancellation token = new Cancellation();
    private long generation;
    private boolean closed, initializing = true;

    RaceModelPanel(
            BuildRaces handle,
            Supplier<BuildRaces> handles,
            LongSupplier revisions,
            long revision,
            long raceId,
            List<RaceTypes.PlayerClass> recorded) {
        super(12);
        this.handle = handle;
        this.handles = handles;
        this.revisions = revisions;
        this.revision = revision;
        this.raceId = raceId;
        voices = new RaceVoicePanel(handle, this::stale);
        classes.getItems().setAll(recorded);
        classes.setConverter(
                new javafx.util.StringConverter<>() {
                    public String toString(RaceTypes.PlayerClass value) {
                        return value == null ? "No recorded class" : value.name();
                    }

                    public RaceTypes.PlayerClass fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        variants.setConverter(
                new javafx.util.StringConverter<>() {
                    public String toString(RaceMediaTypes.Variant value) {
                        return value == null ? "No recorded model" : value.label();
                    }

                    public RaceMediaTypes.Variant fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        classes.setAccessibleText("Recorded class for model preview");
        variants.setAccessibleText("Recorded model variant");
        classes.getStyleClass().add("build-choice");
        variants.getStyleClass().add("build-choice");
        classes.setMaxWidth(Double.MAX_VALUE);
        variants.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(classes, Priority.ALWAYS);
        HBox.setHgrow(variants, Priority.ALWAYS);
        if (!recorded.isEmpty()) {
            classes.getSelectionModel().selectFirst();
        }
        classes.setOnAction(
                event -> {
                    if (!initializing) {
                        load();
                    }
                });
        variants.setOnAction(
                event -> {
                    if (!initializing) {
                        load();
                    }
                });
        HBox choices = new HBox(12, new Label("Class"), classes, new Label("Model"), variants);
        state.getStyleClass().add("muted");
        state.setWrapText(true);
        TitledPane source = new TitledPane("Model source details", evidence);
        source.setExpanded(false);
        getChildren().addAll(choices, state, viewer, voices, source);
        handle.modelVariants(raceId, token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale()) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.variants().isEmpty()) {
                                                state.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Race model references are unavailable.");
                                                viewer.clear("No model surface is available");
                                                return;
                                            }
                                            variants.getItems().setAll(result.variants());
                                            variants.getSelectionModel().selectFirst();
                                            initializing = false;
                                            load();
                                        }));
    }

    private void load() {
        var model = variants.getValue();
        if (model == null) {
            return;
        }
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        state.setText("Loading model surface…");
        viewer.clear("Loading static model…");
        var selected = classes.getValue();
        long classId = selected == null ? 0 : selected.id();
        voices.selection(raceId, classId, model.sex());
        handle.preview(raceId, classId, model.chrModelId(), token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale() || request != generation) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.mesh() == null) {
                                                state.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Model surface is unavailable.");
                                                viewer.clear("No static surface is available");
                                                return;
                                            }
                                            state.setText(
                                                    "Static model preview · sample customization · class starting outfit unavailable");
                                            evidence.getChildren()
                                                    .setAll(
                                                            new Label(
                                                                    "ChrModel "
                                                                            + model.chrModelId()
                                                                            + " · Display "
                                                                            + model.displayId()
                                                                            + " · M2 FileDataID "
                                                                            + model
                                                                                    .modelFileDataId()),
                                                            new Label(
                                                                    result.restored()
                                                                            ? "Restored derived surface"
                                                                            : "Derived from the original M2 and SKIN"));
                                            for (String issue : result.issues()) {
                                                var note = new Label(issue);
                                                note.setWrapText(true);
                                                note.getStyleClass().add("muted");
                                                evidence.getChildren().add(note);
                                            }
                                            viewer.setMesh(result.mesh(), result.visibleParts());
                                        }));
    }

    private boolean stale() {
        return closed || handles.get() != handle || revisions.getAsLong() != revision;
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        viewer.close();
        voices.close();
    }
}
