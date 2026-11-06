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
    private final javafx.scene.control.Label hotfixSource = new javafx.scene.control.Label();
    private Button importHotfix, baseData, reopenHotfix;
    private javafx.scene.control.CheckBox watchHotfix;
    private boolean watchSync;

    /** Client hotfix cache actions owned by the application window. */
    interface HotfixActions {
        /** Choose and import a cache; {@code watch} remembers it for Refresh sources. */
        void importCache(boolean watch);

        void useBaseData();

        /** Forget the remembered original; binding and imported files stay unchanged. */
        void stopWatching();

        /** Reopen so a refresh-imported cache replaces the loaded snapshot. */
        void reopen();
    }
    private TalentsView talents;
    private RacesView races;
    private NpcsView npcs;
    private QuestsView quests;
    private WorldView world;
    private MapsView maps;
    private MountsView mounts;
    private ProfessionsView professions;
    private DungeonsView dungeons;
    private final DataSourcesService sources;
    private WorldBookmarkStore worldBookmarks;
    private boolean closed;
    private long workspaceRevision;
    private String currentArea = "Overview";

    /** Application-owned World bookmarks; null keeps session-only bookmarks. */
    void worldBookmarks(WorldBookmarkStore store) {
        worldBookmarks = store;
        if (world != null) world.bookmarks(store);
    }

    public PreparationPane(
            BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich) {
        this(job,choose,release,enrich,null);
    }

    public PreparationPane(BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich, BuildDownloads downloads) {
        this(job,choose,release,enrich,downloads,null);
    }

    public PreparationPane(BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich,
            BuildDownloads downloads, DataSourcesService sources) {
        this(job, choose, release, enrich, downloads, sources, null);
    }

    PreparationPane(BuildPreparation.Job job, Runnable choose, Runnable release, Runnable enrich,
            BuildDownloads downloads, DataSourcesService sources,
            HotfixActions hotfix) {
        this.job = job;
        this.sources = sources;
        var navigation = new HBox(4);
        navigation.getStyleClass().add("area-navigation");
        for (String area : List.of("Overview", "Files", "Tables", "Items", "Creatures", "NPCs", "Quests", "Talents", "Races", "Dungeons", "Mounts", "Professions", "World", "Maps")) {
            var button = new Button(area);
            button.getStyleClass().add("area-link");
            button.setOnAction(event -> show(area));
            areas.put(area, button);
            navigation.getChildren().add(button);
        }
        var navigationScroll = new ScrollPane(navigation);
        navigationScroll.setFitToHeight(true);
        navigationScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navigationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        navigationScroll.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        setTop(navigationScroll);
        retry = new Button("Retry / enrich");
        retry.setOnAction(event -> enrich.run());
        cancel = new Button("Cancel preparation");
        cancel.setOnAction(event -> job.cancel());
        var closeButton = new Button("Close build");
        closeButton.setOnAction(event -> release.run());
        var controls = new HBox(10, cancel, retry, closeButton);
        if (hotfix != null) {
            importHotfix = new Button("Import client hotfix cache…");
            importHotfix.setTooltip(new javafx.scene.control.Tooltip(
                    "Use a local client cache for this build and locale, including cached "
                            + "decryption keys. The workspace reopens after verification."));
            importHotfix.setOnAction(event -> hotfix.importCache(watchHotfix.isSelected()));
            baseData = new Button("Use base data");
            baseData.setTooltip(new javafx.scene.control.Tooltip(
                    "Stop applying this build and locale's client cache. Imported files remain stored locally."));
            baseData.setOnAction(event -> hotfix.useBaseData());
            watchHotfix = new javafx.scene.control.CheckBox("Check on Refresh sources");
            watchHotfix.setTooltip(new javafx.scene.control.Tooltip(
                    "Remember the imported file's location privately. Refresh sources then verifies "
                            + "and imports a newer cache for this exact build and locale; reopen the "
                            + "build to apply it. Turning this on asks for the file again."));
            watchHotfix.selectedProperty().addListener((property, was, selected) -> {
                if (watchSync) return;
                if (selected) hotfix.importCache(true);
                else hotfix.stopWatching();
            });
            reopenHotfix = new Button("Reopen with newer cache");
            reopenHotfix.setOnAction(event -> hotfix.reopen());
            reopenHotfix.setVisible(false);
            reopenHotfix.setManaged(false);
            hotfixSource.setWrapText(true);
            hotfixSource.getStyleClass().add("muted");
            controls.getChildren().addAll(importHotfix, baseData, watchHotfix, reopenHotfix);
        }
        liveOverview =
                BuildOverviewView.live(job, this::show,
                        new javafx.scene.layout.VBox(8, controls, hotfixSource), downloads, sources);
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
        if (importHotfix != null) {
            boolean unavailable = !(job.work() instanceof NativePreparationWork work) || !work.canImportHotfix();
            importHotfix.setDisable(snapshot.busy() || job.enrichingNames() || unavailable);
            baseData.setDisable(snapshot.busy() || job.enrichingNames() || unavailable);
            watchHotfix.setDisable(snapshot.busy() || job.enrichingNames() || unavailable);
            boolean watched = job.work() instanceof NativePreparationWork work && work.hotfixWatched();
            if (watchHotfix.isSelected() != watched) {
                watchSync = true;
                try { watchHotfix.setSelected(watched); } finally { watchSync = false; }
            }
            boolean pending = job.work() instanceof NativePreparationWork work && work.hotfixRefreshPending();
            reopenHotfix.setVisible(pending);
            reopenHotfix.setManaged(pending);
            reopenHotfix.setDisable(snapshot.busy() || job.enrichingNames());
            hotfixSource.setText(job.work() instanceof NativePreparationWork work
                    ? work.hotfixStatus() : "Base data");
        }
        if (job.work() instanceof NativePreparationWork work
                && workspaceRevision != work.revision()) {
            workspaceRevision = work.revision();
            browsers.values().forEach(ResearchBrowser::invalidate);
            if (talents != null) talents.invalidate();
            if (maps != null) maps.invalidate();
            if (races != null) races.invalidate();
            if (npcs != null) npcs.invalidate();
            if (quests != null) quests.invalidate();
            if (world != null) world.invalidate();
            if (mounts != null) mounts.invalidate();
            if (professions != null) professions.invalidate();
            if (dungeons != null) dungeons.invalidate();
            if (currentArea.equals("Races") && races != null) races.ensureLoaded();
            if (currentArea.equals("Talents") && talents != null) talents.ensureLoaded();
            var visible = browsers.get(currentArea);
            if (visible != null) visible.ensureLoaded();
        }
        if (currentArea.equals("NPCs") && npcs != null) npcs.ensureLoaded();
        if (currentArea.equals("Quests") && quests != null) quests.ensureLoaded();
        if (currentArea.equals("World") && world != null) world.ensureLoaded();
        if (currentArea.equals("Maps") && maps != null) maps.ensureLoaded();
        if (currentArea.equals("Dungeons") && dungeons != null) dungeons.ensureLoaded();
        if (currentArea.equals("Mounts") && mounts != null) mounts.ensureLoaded();
        if (currentArea.equals("Professions") && professions != null) professions.ensureLoaded();
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
        if (area.equals("Dungeons") && job.work() instanceof NativePreparationWork work) {
            if (dungeons == null) {
                dungeons = new DungeonsView(work::dungeonsHandle, work::revision, job.build().version() + " · " + job.locale(), work::icon);
                dungeons.interiorOpener(d -> GlobalInteriorView.open(work::worldHandle, work::locationMapsHandle, d.mapId(), d.name()));
            }
            setCenter(dungeons); dungeons.ensureLoaded(); return;
        }
        if (area.equals("Mounts") && job.work() instanceof NativePreparationWork work) {
            if (mounts == null) mounts = new MountsView(work::mountsHandle, work::revision, job.build().version() + " · " + job.locale());
            setCenter(mounts); mounts.ensureLoaded(); return;
        }
        if (area.equals("Professions") && job.work() instanceof NativePreparationWork work) {
            if (professions == null) professions = new ProfessionsView(work::professionsHandle, work::revision, work::icon, work::itemName);
            setCenter(professions); professions.ensureLoaded(); return;
        }
        if (area.equals("Maps") && job.work() instanceof NativePreparationWork work) {
            if (maps == null) {
                maps = new MapsView(work::locationMapsHandle, work::revision, work::tablesHandle);
                if (sources != null) attachMapRoles(maps, work);
            }
            setCenter(maps); maps.ensureLoaded(); return;
        }
        if (area.equals("World")) {
            if (world == null) {
                world = new WorldView(job);
                world.bookmarks(worldBookmarks);
                if (job.work() instanceof NativePreparationWork work)
                    world.attachFileNames(id -> WorldSpawnModels.path(work.filesHandle(), id,
                            com.vibeminer.access.Cancellation.NONE));
                if (sources != null && job.work() instanceof NativePreparationWork work) {
                    var overlay = new WorldSpawnOverlay(token -> sources.worldSpawns(job.build(), token),
                            () -> java.util.List.of(sources.worldSpawnsKey(job.build()), work.revision()));
                    overlay.attachModels(new WorldSpawnModels(
                            WorldSpawnModels.build(work::creaturesHandle, work::filesHandle, job.build().version()),
                            work::revision, javafx.application.Platform::runLater));
                    world.attachSpawns(overlay);
                    attachWorldRoles(world, work);
                    var generatedStore = work.generatedHandle();
                    world.attachCreatures(new WorldTileCreatures(
                            token -> sources.worldSpawns(job.build(), token),
                            () -> java.util.List.of(sources.worldSpawnsKey(job.build()), work.revision()),
                            WorldTileCreatures.chooser(work::creaturesHandle),
                            new WorldTileCreatures.DisplayPreviews(() -> {
                                var tables = work.tablesHandle();
                                var files = work.filesHandle();
                                return tables == null || files == null ? null
                                        : new com.vibeminer.concepts.models.DisplayModelService(
                                                tables, files, generatedStore);
                            }, work::revision)));
                }
            }
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
        if (maps != null) maps.close();
        if (mounts != null) mounts.close();
        if (professions != null) professions.close();
        if (dungeons != null) dungeons.close();
    }

    private void showSourceTable(String table) {
        show("Tables");
        browsers.get("Tables").searchTable(table);
    }

    /**
     * Minimap role markers: the Maps placement carried to world yards through the selected build's
     * recorded assignment frames of the explorer's current map.
     */
    private void attachWorldRoles(WorldView view, NativePreparationWork work) {
        var build = job.build();
        var locale = job.locale();
        view.attachRoles(new WorldRoleOverlay((worldMapId, token) -> {
            var source = work.locationMapsHandle();
            if (source == null) throw new IllegalStateException("Selected-build map tables are unavailable.");
            var placement = placeRoles(source, sources.mapRoles(build, locale, token), token);
            var frames = source.worldFrames(worldMapId, token).join();
            return new WorldRoleOverlay.Loaded(placement.catalog(),
                    com.vibeminer.concepts.locations.MapRoles.world(placement, frames));
        }, () -> java.util.Arrays.asList(sources.mapRolesKey(build), work.revision(),
                work.locationMapsHandle())));
    }

    /** Resolves a role catalog in bounded chunks so interactive map requests can interleave. */
    private static com.vibeminer.concepts.locations.MapRoles.Placement placeRoles(
            com.vibeminer.concepts.locations.BuildLocationMaps source,
            com.vibeminer.concepts.locations.MapRoles.Catalog catalog,
            com.vibeminer.access.Cancellation token) {
        var locations = com.vibeminer.concepts.locations.MapRoles.locations(catalog);
        var resolutions = new java.util.ArrayList<com.vibeminer.concepts.locations.MapTypes.Resolution>(locations.size());
        for (int at = 0; at < locations.size(); at += 512) {
            token.check();
            resolutions.addAll(source.resolveAll(
                    locations.subList(at, Math.min(locations.size(), at + 512)), token).join());
        }
        return com.vibeminer.concepts.locations.MapRoles.place(catalog, resolutions);
    }

    /** Quest/service overlays: local prepared Questie roles resolved by the selected build's maps. */
    private void attachMapRoles(MapsView view, NativePreparationWork work) {
        var build = job.build();
        var locale = job.locale();
        view.attachRoles(new MapRoleOverlay(token -> {
            var source = work.locationMapsHandle();
            if (source == null) throw new IllegalStateException("Selected-build map tables are unavailable.");
            // Bounded chunks let interactive artwork and location requests interleave on the shared worker.
            return placeRoles(source, sources.mapRoles(build, locale, token), token);
        }, () -> java.util.Arrays.asList(sources.mapRolesKey(build), work.revision(),
                work.locationMapsHandle()), view::redraw));
    }
}
