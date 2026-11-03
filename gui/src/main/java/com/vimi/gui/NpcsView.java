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
