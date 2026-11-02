package com.vimi.gui;

import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Stable popup controls: progress updates do not recreate focused buttons. */
public final class PreparationPopup extends VBox {
    private final MockPreparationService.Job job;
    private final Label stage = label("", "h2");
    private final Label problem = label("", "preparation-message");
    private final Label navigation = label("", "muted");
    private final ProgressIndicator progress = new ProgressIndicator(-1);
    private final Label progressNote = label("Simulated work · no overall percentage estimated", "muted");
    private final List<Label> steps = MockPreparationService.STAGES.stream().map(name -> label(name, "muted")).toList();
    private final Button action = new Button("Cancel opening");
    private final Button next = new Button("Next stage");
    private final Button fail = new Button("Simulate failure");
    private final Button hide = new Button("Hide");

    public PreparationPopup(MockPreparationService.Job job, Runnable onHide, Runnable onCancel,
            Runnable onRetry, Runnable onNext, Runnable onFail) {
        super(16);
        this.job = job;
        setPadding(new Insets(26));
        setMaxWidth(570);
        setMaxHeight(Region.USE_PREF_SIZE);
        getStyleClass().add("preparation-popup");
        Label heading = label("Opening " + job.build().version(), "h1");
        heading.setFocusTraversable(true);
        heading.setAccessibleText("Opening build " + job.build().version() + ". Simulated preparation.");
        progress.setPrefSize(24, 24);
        progress.setMinSize(24, 24);
        progress.setMaxSize(24, 24);
        HBox working = new HBox(10, progress, progressNote);
        working.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        progress.setAccessibleText("Current stage progress is indeterminate");
        problem.setMinHeight(48);
        getChildren().addAll(label("SIMULATED PREPARATION", "badge"), heading,
                label("World of Warcraft · " + job.build().edition() + " · " + job.build().product() + " · " + job.region() + " source", "muted"),
                label("Preparing the metadata needed for Overview. Game content stays on demand.", "subtitle"), stage, working);
        VBox stages = new VBox(8);
        stages.getChildren().addAll(steps);
        getChildren().addAll(stages, problem, navigation);
        hide.setOnAction(event -> onHide.run());
        action.setOnAction(event -> {
            if (job.active()) {
                onCancel.run();
            } else {
                onRetry.run();
            }
        });
        next.setOnAction(event -> onNext.run());
        fail.setOnAction(event -> onFail.run());
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox actions = new HBox(10, hide, space, action);
        HBox demo = new HBox(10, label("Mock controls", "eyebrow"), next, fail);
        demo.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        demo.getStyleClass().add("preparation-demo");
        getChildren().addAll(actions, demo);
        update(false);
    }

    public void focusHeading() {
        getChildren().get(1).requestFocus();
    }

    public List<Button> controls() { return List.of(hide, action, next, fail); }

    public void update(boolean anotherBuildActive) {
        var state = job.state();
        boolean running = state == MockPreparationService.State.RUNNING;
        stage.setText(switch (state) {
            case RUNNING -> "[" + (job.stage() + 1) + "/4] " + MockPreparationService.STAGES.get(job.stage());
            case STOPPING -> "Stopping opening…";
            case FAILED -> "Opening needs attention";
            case CANCELED -> "Opening canceled";
            case READY -> "Overview is ready";
        });
        progress.setVisible(job.active());
        progressNote.setText(job.active() ? "Simulated work · no overall percentage estimated" : "No work running");
        for (int index = 0; index < steps.size(); index++) {
            boolean done = index < job.stage() || state == MockPreparationService.State.READY;
            steps.get(index).setText((done ? "✓  " : index == job.stage() ? "›  " : "    ") + MockPreparationService.STAGES.get(index));
            steps.get(index).getStyleClass().setAll(index == job.stage() && running ? "value" : "muted");
        }
        problem.setText(switch (state) {
            case RUNNING -> switch (job.stage()) {
                case 0 -> "Checking the exact build and CDN configurations. Reusable verified metadata can be kept.";
                case 1 -> "Reading the published download manifest. This is metadata work, not Download all.";
                case 2 -> "Assessing retained encoded content. No overall percentage is estimated.";
                default -> "Publishing the Overview context. The file catalog and research indexes do not block entry.";
            };
            case STOPPING -> "Cancellation requested. Waiting for the simulated operation to retire.";
            case FAILED -> job.problem();
            case CANCELED -> "Preparation stopped. Nothing will restart until you choose Retry opening.";
            case READY -> "Required metadata is ready. Research-specific preparation can continue separately.";
        });
        navigation.setText(job.active()
                ? "Overview opens automatically while this popup is visible. Hide keeps preparation running and prevents automatic navigation."
                : anotherBuildActive ? "Another build is preparing. Retry becomes available after it finishes."
                : "Hide returns to the build list. Retry starts a new simulated attempt.");
        action.setText(state == MockPreparationService.State.STOPPING ? "Stopping…"
                : running ? "Cancel opening" : "Retry opening");
        action.setDisable(state == MockPreparationService.State.STOPPING || state == MockPreparationService.State.READY || (!job.active() && anotherBuildActive));
        next.setDisable(!running);
        fail.setDisable(!running);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
