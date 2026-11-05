package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceMediaTypes;
import com.vibeminer.concepts.races.RaceCustomizationTypes;
import com.vibeminer.concepts.races.RaceTypes;

import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Selected race/model/class media requests; owns no build or domain handle. */
final class RaceModelPanel extends VBox implements AutoCloseable {
    private final BuildRaces handle;
    private final Supplier<BuildRaces> handles;
    private final LongSupplier revisions;
    private final long revision, raceId;
    private final ComboBox<RaceTypes.PlayerClass> classes = new ComboBox<>();
    private final ComboBox<RaceMediaTypes.Variant> variants = new ComboBox<>();
    private final Label state = new Label("Loading model variants…");
    private final Label definition = new Label();
    private RaceMediaTypes.Variant assessedModel;
    private final ModelPreviewView viewer = new ModelPreviewView();
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final VBox evidence = new VBox(7);
    private final RaceVoicePanel voices;
    private final LegacyWorldActivity requests = new LegacyWorldActivity("race-model-requests", true);
    private final VBox appearanceControls = new VBox(8);
    private final Label controlsState = new Label("Select a model to inspect appearance choices.");
    private final Button resetAppearance = new Button("Reset appearance");
    private final java.util.Map<Long, Long> picks = new java.util.TreeMap<>();
    private final java.util.Map<Long, ComboBox<RaceCustomizationTypes.Choice>> pickers = new java.util.HashMap<>();
    private RaceCustomizationTypes.Catalog customization;
    private RaceMediaTypes.Idle idleFacts;
    private long shownModel = -1, shownClass = -1;
    private boolean updatingChoices, waitingForRequests;

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
        voices = new RaceVoicePanel(handle, this::workspaceStale);
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
        activity.setId("race-model-activity");
        activity.setPrefSize(18, 18);
        activity.setMaxSize(18, 18);
        activity.setAccessibleText("Preparing textures and starting clothing");
        activity.setVisible(false);
        activity.managedProperty().bind(activity.visibleProperty());
        classes.setId("race-model-class");
        variants.setId("race-model-variant");
        state.setId("race-model-state");
        definition.setId("race-model-definition");
        definition.setWrapText(true);
        definition.getStyleClass().add("muted");
        definition.setVisible(false);
        definition.managedProperty().bind(definition.visibleProperty());
        appearanceControls.setId("race-appearance-controls");
        controlsState.setWrapText(true);
        controlsState.getStyleClass().add("muted");
        resetAppearance.setId("race-appearance-reset");
        resetAppearance.setDisable(true);
        resetAppearance.setOnAction(event -> {
            picks.clear();
            updatingChoices = true;
            if (customization != null) for (var option : customization.options()) {
                var picker = pickers.get(option.id());
                if (picker != null) picker.getSelectionModel().select(option.choices().stream().filter(choice -> choice.id() == option.defaultChoiceId()).findFirst().orElse(null));
            }
            updatingChoices = false;
            resetAppearance.setDisable(true);
            queueLoad();
        });
        var appearanceHeading = new Label("Appearance");
        appearanceHeading.getStyleClass().add("section-title");
        var appearanceCard = new VBox(10, new HBox(12, appearanceHeading, resetAppearance), controlsState, appearanceControls);
        appearanceCard.getStyleClass().add("card");
        getChildren().addAll(choices, definition, appearanceCard, new HBox(9, activity, state), viewer, voices, source);
        viewer.loading("Loading model variants…");
        long variantsRequest = generation;
        request(() -> handle.modelVariants(raceId, token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(variantsRequest)) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.variants().isEmpty()) {
                                                state.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Race model references are"
                                                                        + " unavailable.");
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
        if (stale(generation)) return;
        picks.clear();
        pickers.clear();
        customization = null;
        appearanceControls.getChildren().clear();
        resetAppearance.setDisable(true);
        controlsState.setText("Inspecting recorded appearance choices…");
        queueLoad();
    }

    private void queueLoad() {
        if (stale(generation)) return;
        ++generation;
        activity.setVisible(true);
        state.setText("Preparing selected appearance…");
        if (waitingForRequests) return;
        waitingForRequests = true;
        requests.pauseAndDrain().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            waitingForRequests = false;
            if (stale(generation)) return;
            if (failure != null) {
                activity.setVisible(false);
                state.setText("Previous model work could not retire safely. Reload model records.");
                appearanceControls.setDisable(true);
                return;
            }
            requests.resume();
            token = new Cancellation();
            startLoad();
        }));
    }

    private void startLoad() {
        var model = variants.getValue();
        if (model == null) {
            activity.setVisible(false);
            state.setText("No recorded model is selected.");
            definition.setVisible(false);
            return;
        }
        showDefinition(latest(model));
        long request = generation;
        var selected = classes.getValue();
        long classId = selected == null ? 0 : selected.id();
        if (shownModel == model.chrModelId() && shownClass == classId && customization != null) {
            state.setText("Preparing selected appearance…");
            viewer.appearanceLoading("Preparing selected textures…");
            if (idleFacts == null || idleFacts.variant().chrModelId() != model.chrModelId()) {
                requestIdle(model, request);
            }
            loadAppearance(model, classId, request);
            return;
        }
        state.setText("Loading model surface…");
        activity.setVisible(true);
        evidence.getChildren().clear();
        viewer.loading("Loading static model…");
        voices.selection(raceId, classId, model.sex());
        if (customization == null) loadCustomizations(model, classId, request);
        request(() -> handle.preview(raceId, classId, model.chrModelId(), token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) {
                                                return;
                                            }
                                            // The preview reassesses the definition; show its
                                            // latest result rather than the list's first read.
                                            var assessed =
                                                    result != null
                                                                    && result.variant() != null
                                                                    && result.variant().chrModelId()
                                                                            == model.chrModelId()
                                                            ? result.variant()
                                                            : model;
                                            assessedModel = assessed;
                                            showDefinition(assessed);
                                            if (error != null
                                                    || result == null
                                                    || result.mesh() == null) {
                                                state.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Model surface is unavailable.");
                                                viewer.clear("No static surface is available");
                                                activity.setVisible(false);
                                                return;
                                            }
                                            state.setText(
                                                    "Static model preview · preparing textures and"
                                                            + " starting clothing…");
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
                                                            definitionEvidence(assessed),
                                                            new Label(
                                                                    result.restored()
                                                                            ? "Restored derived"
                                                                                    + " surface"
                                                                            : "Derived from the"
                                                                                    + " original M2"
                                                                                    + " and SKIN"));
                                            for (String issue : result.issues()) {
                                                var note = new Label(issue);
                                                note.setWrapText(true);
                                                note.getStyleClass().add("muted");
                                                evidence.getChildren().add(note);
                                            }
                                            activity.setVisible(true);
                                            viewer.setMesh(
                                                    result.mesh(),
                                                    result.visibleParts(),
                                                    "Preparing textures and starting clothing…");
                                            shownModel = model.chrModelId();
                                            shownClass = classId;
                                            // Textures first: the race worker answers in order,
                                            // and idle can be slow on a first visit.
                                            loadAppearance(model, classId, request);
                                            requestIdle(model, request);
                                        }));
    }

    /** The most recent preview assessment of this model, or the listed one before any. */
    private RaceMediaTypes.Variant latest(RaceMediaTypes.Variant model) {
        return assessedModel != null && assessedModel.chrModelId() == model.chrModelId()
                ? assessedModel
                : model;
    }

    /** Names the selected display's definition only when its recorded source states it. */
    private void showDefinition(RaceMediaTypes.Variant model) {
        definition.setText("Model definition: " + model.definition().label());
        definition.setAccessibleText(definition.getText());
        definition.setVisible(true);
    }

    private static Label definitionEvidence(RaceMediaTypes.Variant model) {
        var value = model.definition();
        var label =
                new Label(
                        "Definition "
                                + value.state()
                                + (value.quality() == null ? "" : " · " + value.quality())
                                + (value.standardDisplayId() > 0
                                        ? " · SD display " + value.standardDisplayId()
                                        : "")
                                + (value.highDisplayId() > 0
                                        ? " · HD display " + value.highDisplayId()
                                        : "")
                                + " · "
                                + value.message());
        label.setWrapText(true);
        return label;
    }

    private void loadAppearance(RaceMediaTypes.Variant model, long classId, long request) {
        var selection = new RaceCustomizationTypes.Request(raceId, classId, model.chrModelId(),
                picks.entrySet().stream().map(entry -> new RaceCustomizationTypes.Pick(entry.getKey(), entry.getValue())).toList());
        request(() -> selection.picks().isEmpty()
                        ? handle.appearance(raceId, classId, model.chrModelId(), token)
                        : handle.appearance(selection, token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.appearance() == null) {
                                                activity.setVisible(false);
                                                viewer.appearanceUnavailable();
                                                state.setText(
                                                        "Static model preview · "
                                                                + (result != null
                                                                                && result.message()
                                                                                        != null
                                                                        ? result.message()
                                                                        : "Textured appearance is"
                                                                                + " unavailable."));
                                                return;
                                            }
                                            String sample =
                                                    result.state() == RaceTypes.State.PARTIAL
                                                            ? "Partial static textured sample"
                                                            : "Static textured sample";
                                            String completedState =
                                                    sample
                                                            + (result.items().isEmpty()
                                                                    ? " · starting clothing"
                                                                            + " unavailable"
                                                                    : " · available starting"
                                                                          + " clothing · held"
                                                                          + " equipment not shown");
                                            state.setText("Rendering composed textures…");
                                            viewer.presentAppearance(
                                                    result.appearance(),
                                                    result.visibleParts(),
                                                    accepted -> {
                                                        if (stale(request)) return;
                                                        activity.setVisible(false);
                                                        state.setText(
                                                                accepted
                                                                        ? completedState
                                                                        : "Static model preview ·"
                                                                                + " textured frame"
                                                                                + " could not be"
                                                                                + " rendered.");
                                                    });
                                            // Re-offer a prepared Stand; it must fit the
                                            // textured sample, or the viewer says it does not.
                                            if (idleFacts != null
                                                    && idleFacts.animation() != null
                                                    && idleFacts.variant().chrModelId()
                                                            == model.chrModelId()) {
                                                viewer.setAnimation(
                                                        idleFacts.animation(),
                                                        idleFacts.message());
                                            }
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
                                                            definitionEvidence(latest(model)),
                                                            new Label(
                                                                    result.restored()
                                                                            ? "Restored composed"
                                                                                    + " appearance"
                                                                            : "Composed from"
                                                                                  + " recorded"
                                                                                  + " character and"
                                                                                  + " item texture"
                                                                                  + " sources"));
                                            if (!result.items().isEmpty()) {
                                                var items =
                                                        new Label(
                                                                "Starting clothing: "
                                                                        + result.items().stream()
                                                                                .map(
                                                                                        item ->
                                                                                                "item #"
                                                                                                        + item
                                                                                                                .itemId()
                                                                                                        + " / display"
                                                                                                        + " #"
                                                                                                        + item
                                                                                                                .displayId())
                                                                                .toList());
                                                items.setWrapText(true);
                                                evidence.getChildren().add(items);
                                            }
                                            for (String issue : result.issues()) {
                                                var note = new Label(issue);
                                                note.setWrapText(true);
                                                note.getStyleClass().add("muted");
                                                evidence.getChildren().add(note);
                                            }
                                            showIdleEvidence();
                                        }));
    }

    /** Assesses Stand idle for the shown model: offers it, or states why it is not offered. */
    private void requestIdle(RaceMediaTypes.Variant model, long request) {
        idleFacts = null;
        request(() -> handle.idle(raceId, model.chrModelId(), token))
                .whenComplete((idle, failure) -> Platform.runLater(() -> {
                    if (stale(request)) return;
                    if (failure != null || idle == null) {
                        viewer.animationUnavailable("Idle animation could not be assessed.");
                        return;
                    }
                    if (idle.variant() == null
                            || idle.variant().chrModelId() != model.chrModelId()) {
                        viewer.animationUnavailable(null);
                        return;
                    }
                    if (idle.state() == RaceTypes.State.READY) {
                        // The viewer offers it only for the exact surface on screen.
                        viewer.setAnimation(idle.animation(), idle.message());
                        idleFacts = idle;
                        showIdleEvidence();
                        return;
                    }
                    if (idle.state() != RaceTypes.State.UNSUPPORTED) {
                        // Unreadable sources: say why; the next selection asks again.
                        viewer.animationUnavailable(
                                "Idle animation could not be assessed: " + idle.message());
                        return;
                    }
                    viewer.animationUnavailable("Idle animation unavailable: " + idle.message());
                    idleFacts = idle;
                    showIdleEvidence();
                }));
    }

    /** Appends the shown model's idle facts; evidence rebuilds call this again. */
    private void showIdleEvidence() {
        if (idleFacts == null || idleFacts.facts().isEmpty()) return;
        var heading = new Label("Idle animation (Stand)");
        heading.setId("race-idle-evidence");
        evidence.getChildren().add(heading);
        for (String line : idleFacts.facts()) {
            var note = new Label(line);
            note.setWrapText(true);
            note.getStyleClass().add("muted");
            evidence.getChildren().add(note);
        }
    }

    private void loadCustomizations(RaceMediaTypes.Variant model, long classId, long request) {
        request(() -> handle.customizations(raceId, classId, model.chrModelId(), token))
                .whenComplete((catalog, failure) -> Platform.runLater(() -> {
                    if (stale(request)) return;
                    if (failure != null || catalog == null
                            || (catalog.state() != RaceTypes.State.READY && catalog.state() != RaceTypes.State.PARTIAL)
                            || catalog.raceId() != raceId
                            || catalog.classId() != classId || catalog.modelId() != model.chrModelId()
                            || catalog.options().isEmpty()) {
                        controlsState.setText(catalog != null && catalog.message() != null
                                ? catalog.message() : "Appearance choices are unavailable for this model.");
                        return;
                    }
                    customization = catalog;
                    updatingChoices = true;
                    appearanceControls.getChildren().clear();
                    pickers.clear();
                    for (var option : catalog.options()) {
                        var picker = new ComboBox<RaceCustomizationTypes.Choice>();
                        picker.setId("race-appearance-option-" + option.id());
                        picker.setAccessibleText(option.label());
                        picker.getItems().setAll(option.choices());
                        picker.setMaxWidth(Double.MAX_VALUE);
                        picker.setConverter(new javafx.util.StringConverter<>() {
                            public String toString(RaceCustomizationTypes.Choice value) { return value == null ? "Select a choice" : value.label(); }
                            public RaceCustomizationTypes.Choice fromString(String value) { throw new UnsupportedOperationException(); }
                        });
                        picker.getSelectionModel().select(option.choices().stream().filter(choice -> choice.id() == option.defaultChoiceId()).findFirst().orElse(null));
                        picker.setOnAction(event -> {
                            if (updatingChoices || picker.getValue() == null) return;
                            if (picker.getValue().id() == option.defaultChoiceId()) picks.remove(option.id());
                            else picks.put(option.id(), picker.getValue().id());
                            resetAppearance.setDisable(picks.isEmpty());
                            queueLoad();
                        });
                        pickers.put(option.id(), picker);
                        appearanceControls.getChildren().add(new VBox(4, new Label(option.label()), picker));
                    }
                    updatingChoices = false;
                    controlsState.setText("Recorded choices · static texture preview; other appearance options are not yet exposed.");
                }));
    }

    private <T> java.util.concurrent.CompletableFuture<T> request(
            Supplier<java.util.concurrent.CompletableFuture<T>> action) {
        var currentToken = token;
        return requests.invoke(currentToken, currentToken::cancel, action);
    }

    private boolean workspaceStale() {
        return closed || handles.get() != handle || revisions.getAsLong() != revision;
    }

    private boolean stale(long request) {
        if (closed || request != generation) return true;
        if (!workspaceStale()) return false;
        viewer.clear("Build changed. Reload model records.");
        close();
        evidence.getChildren().clear();
        state.setText("Build changed. Reload model records.");
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        classes.setDisable(true);
        variants.setDisable(true);
        voices.setDisable(true);
        viewer.setDisable(true);
        generation++;
        requests.closeAsync();
        appearanceControls.setDisable(true);
        resetAppearance.setDisable(true);
        activity.setVisible(false);
        viewer.close();
        voices.close();
    }
}
