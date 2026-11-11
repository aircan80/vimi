package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.npcs.NpcCatalog;
import com.vibeminer.concepts.npcs.NpcSources;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Build-scoped NPCs with field-level evidence, sharing only presentation mechanics. */
final class NpcsView extends ProviderBrowser<NpcCatalog.Npc> {
    NpcsView(BuildPreparation.Job job, DataSourcesService sources) {
        super(
                job,
                sources,
                "NPCs",
                List.of(
                        new Column<>("NPC ID", n -> Long.toString(n.id()), 100),
                        new Column<>("Name", n -> known(n.name()), 240),
                        new Column<>("Title", n -> known(n.subname()), 180),
                        new Column<>("Level", n -> known(n.levels()), 90)));
        if (job != null && job.work() instanceof NativePreparationWork work) {
            previews =
                    new NpcPreviews(
                            work::creaturesHandle,
                            this::displayModels,
                            work::racesHandle,
                            NpcPreviews.egLink(sources, job.build(), job.locale()),
                            job.build().version());
            this.work = work;
            model.idleByDefault(true);
            previewStatus.setWrapText(true);
            previewStatus.getStyleClass().add("muted");
            model.setPrefHeight(320);
            model.setMinHeight(240);
            attachPreview(previewBox);
            attachLocationMap(new LocationMapView(work::locationMapsHandle,
                    () -> new com.vibeminer.concepts.locations.LocationsService(
                            sources == null ? List.of() : sources.locationInputs(job.build(), job.locale())),
                    work::revision, () -> sources == null ? 0L : sources.revision(), false));
        }
    }

    private final ModelPreviewView model = new ModelPreviewView();
    private final javafx.scene.control.Label previewStatus = new javafx.scene.control.Label();
    private final javafx.scene.layout.VBox previewFacts = new javafx.scene.layout.VBox(4);
    private final javafx.scene.layout.VBox previewBox =
            new javafx.scene.layout.VBox(8, model, previewStatus, previewFacts);
    private final java.util.concurrent.ExecutorService previewWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                var thread = new Thread(runnable, "npc-preview");
                thread.setDaemon(true);
                return thread;
            });
    private NpcPreviews previews;
    private NativePreparationWork work;
    private com.vibeminer.concepts.models.BuildDisplayModels displays;
    private Object displaysRevision;
    private Cancellation previewToken = new Cancellation();
    private long previewGeneration;

    /** One display-model component per work revision; replaced when the revision changes. */
    private synchronized com.vibeminer.concepts.models.BuildDisplayModels displayModels() {
        if (work == null || work.tablesHandle() == null || work.filesHandle() == null) return null;
        Object revision = work.revision();
        if (displays == null || !java.util.Objects.equals(revision, displaysRevision)) {
            if (displays != null) displays.closeAsync();
            displays = new com.vibeminer.concepts.models.DisplayModelService(
                    work.tablesHandle(), work.filesHandle(), work.generatedHandle());
            displaysRevision = revision;
        }
        return displays;
    }

    @Override
    void selected(NpcCatalog.Npc row) {
        if (previews == null) return;
        previewToken.cancel();
        previewToken = new Cancellation();
        long request = ++previewGeneration;
        previewFacts.getChildren().clear();
        if (row == null) {
            model.clear("Select an NPC to preview its model");
            previewStatus.setText("");
            return;
        }
        var token = previewToken;
        long npcId = row.id();
        model.loading("Resolving NPC display…");
        previewStatus.setText("Resolving the display of NPC " + npcId + "…");
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return previews.resolve(npcId, token);
                    } catch (Exception failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                }, previewWorker)
                .whenComplete((outcome, failure) -> javafx.application.Platform.runLater(() -> {
                    if (request != previewGeneration) return;
                    if (failure != null || outcome == null) {
                        model.clear("Model preview unavailable");
                        previewStatus.setText("Model preview unavailable: "
                                + (failure == null ? "no result" : rootMessage(failure)));
                        return;
                    }
                    show(outcome, token, request);
                }));
    }

    private void show(NpcPreviews.Outcome outcome, Cancellation token, long request) {
        for (var fact : outcome.facts()) {
            var label = new javafx.scene.control.Label(fact);
            label.setWrapText(true);
            label.getStyleClass().add("muted");
            previewFacts.getChildren().add(label);
        }
        if (outcome.appearance() == null) {
            model.clear("No model to show");
            previewStatus.setText(outcome.reason() == null ? "No model to show." : outcome.reason());
            return;
        }
        if (outcome.textured()) {
            model.setAppearance(outcome.appearance(), outcome.visibleParts(), true);
            previewStatus.setText(outcome.kind() == NpcPreviews.Kind.HUMANOID
                    ? "Character appearance with the NPC's recorded choices and clothing."
                    : "Creature model · display " + outcome.resolution().shown().displayId());
        } else {
            model.setMesh(outcome.appearance().mesh(), outcome.visibleParts(), null);
            previewStatus.setText("Untextured model · " + outcome.reason());
        }
        Long idleDisplay = outcome.idleDisplay();
        var models = displayModels();
        if (idleDisplay == null || models == null) {
            model.animationUnavailable(outcome.kind() == NpcPreviews.Kind.HUMANOID
                    ? "Idle animation is not evaluated for character appearances here." : null);
            return;
        }
        models.idle(idleDisplay, token).whenComplete((idle, failure) ->
                javafx.application.Platform.runLater(() -> {
                    if (request != previewGeneration) return;
                    if (failure != null || idle == null) {
                        model.animationUnavailable(null);
                        return;
                    }
                    switch (idle.state()) {
                        case AVAILABLE -> model.setAnimation(idle.animation(), idle.message());
                        case UNSUPPORTED -> model.animationUnavailable(
                                "Idle animation unavailable: " + idle.message());
                        case UNAVAILABLE -> model.animationUnavailable(null);
                    }
                }));
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void close() {
        previewToken.cancel();
        previewGeneration++;
        previewWorker.shutdownNow();
        synchronized (this) {
            if (displays != null) displays.closeAsync();
            displays = null;
        }
        model.close();
        super.close();
    }

    @Override
    Supplier<Result<NpcCatalog.Npc>> request(NativePreparationWork work, Cancellation cancel) {
        var inputs = new ArrayList<NpcCatalog.Input>();
        if (work.tablesHandle() != null)
            inputs.add(
                    NpcSources.blizzard(
                            work.tablesHandle(),
                            job.build().product(),
                            job.build().version(),
                            job.locale()));
        if (sources != null) inputs.addAll(sources.npcInputs(job.build(), job.locale()));
        return () -> {
            var snapshot = NpcCatalog.load(inputs, cancel);
            return new Result<>(snapshot.npcs(), snapshot.diagnostics());
        };
    }

    @Override
    long id(NpcCatalog.Npc row) {
        return row.id();
    }

    @Override
    String searchable(NpcCatalog.Npc row) {
        return known(row.name()) + " " + known(row.subname());
    }

    @Override
    List<Fact> inspection(NpcCatalog.Npc row) {
        var facts = new ArrayList<Fact>();
        facts.add(new Fact(known(row.name()), "h2"));
        facts.add(new Fact("NPC " + row.id(), "muted"));
        facts.add(
                new Fact(
                        "Field priority: Blizzard → Questie → Polestar → EG Link. Unknown fields"
                            + " fall back; empty values are retained.",
                        "muted"));
        for (var observation : row.evidence()) {
            var source = observation.source();
            facts.add(new Fact(source.provider().toString().replace('_', ' '), "eyebrow"));
            facts.add(
                    new Fact(
                            "Name: "
                                    + known(observation.name())
                                    + evidence(
                                            observation.name(),
                                            row.name(),
                                            row.evidence().stream()
                                                            .filter(o -> o.name() != null)
                                                            .findFirst()
                                                            .orElse(null)
                                                    == observation),
                            "value"));
            facts.add(
                    new Fact(
                            "Title: "
                                    + known(observation.subname())
                                    + evidence(
                                            observation.subname(),
                                            row.subname(),
                                            row.evidence().stream()
                                                            .filter(o -> o.subname() != null)
                                                            .findFirst()
                                                            .orElse(null)
                                                    == observation),
                            "value"));
            facts.add(
                    new Fact(
                            "Level: "
                                    + known(observation.levels())
                                    + evidence(
                                            observation.levels(),
                                            row.levels(),
                                            row.evidence().stream()
                                                            .filter(o -> o.levels() != null)
                                                            .findFirst()
                                                            .orElse(null)
                                                    == observation),
                            "value"));
            facts.add(
                    new Fact(
                            provenance(
                                    source.product(),
                                    source.build(),
                                    source.locale(),
                                    source.version()),
                            "muted"));
        }
        return facts;
    }
}
