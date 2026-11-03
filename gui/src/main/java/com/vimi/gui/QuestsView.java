package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.quests.QuestCatalog;
import com.vibeminer.concepts.quests.QuestSources;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Build-scoped Quests with field-level evidence, sharing only presentation mechanics. */
final class QuestsView extends ProviderBrowser<QuestCatalog.Quest> {
    QuestsView(BuildPreparation.Job job, DataSourcesService sources) {
        super(
                job,
                sources,
                "Quests",
                List.of(
                        new Column<>("Quest ID", n -> Long.toString(n.id()), 100),
                        new Column<>("Title", n -> known(n.title()), 240),
                        new Column<>("Required level", n -> known(n.requiredLevel()), 180),
                        new Column<>("Level", n -> known(n.questLevel()), 90)));
    }

    @Override
    Supplier<Result<QuestCatalog.Quest>> request(NativePreparationWork work, Cancellation cancel) {
        var inputs = new ArrayList<QuestCatalog.Input>();
        if (work.tablesHandle() != null)
            inputs.add(
                    QuestSources.blizzard(
                            work.tablesHandle(),
                            job.build().product(),
                            job.build().version(),
                            job.locale()));
        if (sources != null) inputs.addAll(sources.questInputs(job.build(), job.locale()));
        return () -> {
            var snapshot = QuestCatalog.load(inputs, cancel);
            return new Result<>(snapshot.quests(), snapshot.diagnostics());
        };
    }

    @Override
    long id(QuestCatalog.Quest row) {
        return row.id();
    }

    @Override
    String searchable(QuestCatalog.Quest row) {
        return known(row.title());
    }

    @Override
    List<Fact> inspection(QuestCatalog.Quest row) {
        var facts = new ArrayList<Fact>();
        facts.add(new Fact(known(row.title()), "h2"));
        facts.add(new Fact("Quest " + row.id(), "muted"));
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
                            "Title: "
                                    + known(observation.title())
                                    + evidence(
                                            observation.title(),
                                            row.title(),
                                            row.evidence().stream()
                                                            .filter(o -> o.title() != null)
                                                            .findFirst()
                                                            .orElse(null)
                                                    == observation),
                            "value"));
            facts.add(
                    new Fact(
                            "Required level: "
                                    + known(observation.requiredLevel())
                                    + evidence(
                                            observation.requiredLevel(),
                                            row.requiredLevel(),
                                            row.evidence().stream()
                                                            .filter(o -> o.requiredLevel() != null)
                                                            .findFirst()
                                                            .orElse(null)
                                                    == observation),
                            "value"));
            facts.add(
                    new Fact(
                            "Level: "
                                    + known(observation.questLevel())
                                    + evidence(
                                            observation.questLevel(),
                                            row.questLevel(),
                                            row.evidence().stream()
                                                            .filter(o -> o.questLevel() != null)
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
