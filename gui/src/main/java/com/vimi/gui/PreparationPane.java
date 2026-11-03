package com.vimi.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/** Real build workspace. Table-backed areas share one initial readiness gate. */
public final class PreparationPane extends BorderPane implements AutoCloseable {
    private final BuildPreparation.Job job;
    private final BuildOverviewView.Live liveOverview;
    private final Map<String, Button> areas = new LinkedHashMap<>();
    private final Map<String, ResearchBrowser> browsers = new LinkedHashMap<>();
    private final Timeline clock;
    private final Button retry, cancel;
    private final Node overview;
    private TalentsView talents;
    private RacesView races;
    private NpcsView npcs;
    private QuestsView quests;
    private WorldView world;
    private final DataSourcesService sources;
    private boolean closed;
    private long workspaceRevision;
    private String currentArea = "Overview";

    public PreparationPane(
            BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich) {
        this(job,choose,release,enrich,null);
    }

    public PreparationPane(BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich, BuildDownloads downloads) {
        this(job,choose,release,enrich,downloads,null);
    }

    public PreparationPane(BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich,
            BuildDownloads downloads, DataSourcesService sources) {
        this.job = job;
        this.sources = sources;
        var navigation = new HBox(4);
        navigation.getStyleClass().add("area-navigation");
        for (String area : List.of("Overview", "Files", "Tables", "Items", "Creatures", "NPCs", "Quests", "Talents", "Races", "World")) {
            var button = new Button(area);
            button.getStyleClass().add("area-link");
            button.setOnAction(event -> show(area));
            areas.put(area, button);
            navigation.getChildren().add(button);
        }
        setTop(navigation);
        retry = new Button("Retry / enrich");
        retry.setOnAction(event -> enrich.run());
        cancel = new Button("Cancel preparation");
        cancel.setOnAction(event -> job.cancel());
        var closeButton = new Button("Close build");
        closeButton.setOnAction(event -> release.run());
        liveOverview =
                BuildOverviewView.live(job, this::show, new HBox(10, cancel, retry, closeButton), downloads, sources);
        overview = liveOverview.node();
        show("Overview");
        clock = new Timeline(new KeyFrame(Duration.millis(250), event -> update()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        update();
    }

    public static String describe(BuildPreparation.Snapshot snapshot) {
        return PreparationStatus.describe(snapshot);
    }

    private void update() {
        if (closed) return;
        var snapshot = job.snapshot();
        if (job.work() instanceof NativePreparationWork work
                && workspaceRevision != work.revision()) {
            workspaceRevision = work.revision();
            browsers.values().forEach(ResearchBrowser::invalidate);
            if (talents != null) talents.invalidate();
            if (races != null) races.invalidate();
            if (npcs != null) npcs.invalidate();
            if (quests != null) quests.invalidate();
            if (world != null) world.invalidate();
            if (currentArea.equals("Races") && races != null) races.ensureLoaded();
            if (currentArea.equals("Talents") && talents != null) talents.ensureLoaded();
            var visible = browsers.get(currentArea);
            if (visible != null) visible.ensureLoaded();
        }
        if (currentArea.equals("NPCs") && npcs != null) npcs.ensureLoaded();
        if (currentArea.equals("Quests") && quests != null) quests.ensureLoaded();
        if (currentArea.equals("World") && world != null) world.ensureLoaded();
        liveOverview.update(snapshot, job.enrichingNames(), job.namesProblem());
        cancel.setDisable(!snapshot.busy() && !job.enrichingNames());
        retry.setDisable(snapshot.busy() || job.enrichingNames());
        for (var entry : areas.entrySet()) {
            boolean enabled = PreparationStatus.available(entry.getKey(), snapshot.ready());
            entry.getValue().setDisable(!enabled);
            entry.getValue()
                    .setText(PreparationStatus.tab(entry.getKey(), snapshot, job.enrichingNames()));
            boolean active =
                    entry.getKey().equals("Files")
                            ? job.enrichingNames()
                                    || snapshot.busy()
                                            && snapshot.phase() == BuildPreparation.Phase.FILES
                            : entry.getKey().equals("Tables")
                                    && snapshot.busy()
                                    && snapshot.phase().ordinal()
                                            >= BuildPreparation.Phase.KEYS.ordinal();
            if (active && entry.getValue().getGraphic() == null) {
                var indicator = new javafx.scene.control.ProgressIndicator(-1);
                indicator.setPrefSize(14, 14);
                indicator.setMaxSize(14, 14);
                entry.getValue().setGraphic(indicator);
            } else if (!active) entry.getValue().setGraphic(null);
            entry.getValue()
                    .setAccessibleHelp(
                            enabled ? "Ready to browse" : "Waiting for initial preparation");
        }
    }

    private void show(String area) {
        if(world!=null && !area.equals("World")) world.deactivate();
        currentArea = area;
        areas.forEach(
                (name, button) -> {
                    button.getStyleClass().remove("area-active");
                    if (name.equals(area)) button.getStyleClass().add("area-active");
                });
        if (area.equals("Overview")) {
            var scroll = new ScrollPane(overview);
            scroll.setFitToWidth(true);
            setCenter(scroll);
            return;
        }
        if (area.equals("World")) {
            if (world == null) world = new WorldView(job);
            setCenter(world); world.activate(); return;
        }
        if (area.equals("Quests")) {
            if (quests == null) quests = new QuestsView(job, sources);
            setCenter(quests); quests.ensureLoaded(); return;
        }
        if (area.equals("NPCs")) {
            if (npcs == null) npcs = new NpcsView(job, sources);
            setCenter(npcs); npcs.ensureLoaded(); return;
        }
        if (area.equals("Talents")) {
            if (talents == null) talents = new TalentsView(job);
            setCenter(talents);
            talents.ensureLoaded();
            return;
        }
        if (area.equals("Races")) {
            if (races == null) races = new RacesView(job);
            setCenter(races);
            races.ensureLoaded();
            return;
        }
        var browser =
                browsers.computeIfAbsent(
                        area, name -> new ResearchBrowser(name, job, this::showSourceTable));
        setCenter(browser);
        browser.ensureLoaded();
    }

    @Override
    public void close() {
        closed = true;
        clock.stop();
        browsers.values().forEach(ResearchBrowser::close);
        if (talents != null) talents.close();
        if (races != null) races.close();
        if (npcs != null) npcs.close();
        if (quests != null) quests.close();
        if (world != null) world.close();
    }

    private void showSourceTable(String table) {
        show("Tables");
        browsers.get("Tables").searchTable(table);
    }
}
