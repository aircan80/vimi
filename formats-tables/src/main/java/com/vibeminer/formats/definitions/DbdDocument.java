package com.vibeminer.formats.definitions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.definitions.DbdTypes.BuildVersion;
import com.vibeminer.formats.definitions.DbdTypes.Column;
import com.vibeminer.formats.definitions.DbdTypes.LayoutCandidate;
import com.vibeminer.formats.definitions.DbdTypes.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A parsed definition; layout fallback never verifies names for an unlisted build. */
public record DbdDocument(List<Column> columns, List<Block> blocks, long selectionCost) {
    public DbdDocument {
        columns = List.copyOf(columns);
        blocks = List.copyOf(blocks);
    }

    public boolean listsBuild(String build, Cancellation token) {
        return !selectBuild(build, null, token).blocks().isEmpty();
    }

    public Selection selectBuild(String version, String layout, Cancellation token) {
        token.check();
        var build = BuildVersion.parse(version);
        if (layout != null && !DbdDefinitionReader.isLayout(layout)) {
            throw DbdDefinitionReader.error("DBD_INVALID", "Complete eight-hex layout required");
        }
        var found = new ArrayList<Block>();
        for (var block : blocks) {
            token.check();
            if (block.builds().stream().anyMatch(r -> r.contains(build))
                    && (layout == null
                            || block.layoutHashes().stream()
                                    .anyMatch(h -> h.equalsIgnoreCase(layout)))) {
                found.add(block);
            }
        }
        return new Selection(
                found.isEmpty() ? "missing" : found.size() == 1 ? "candidate" : "ambiguous",
                found,
                "build");
    }

    public Selection selectFile(String version, String layout, Cancellation token) {
        if (listsBuild(version, token)) {
            return selectBuild(version, layout, token);
        }
        if (!DbdDefinitionReader.isLayout(layout)) {
            throw DbdDefinitionReader.error("DBD_INVALID", "Complete eight-hex layout required");
        }
        var build = BuildVersion.parse(version);
        var ranked = new ArrayList<Ranked>();
        for (var block : blocks) {
            token.check();
            Long distance = distance(block, build);
            if (distance != null
                    && block.layoutHashes().stream().anyMatch(h -> h.equalsIgnoreCase(layout))) {
                ranked.add(new Ranked(block, distance));
            }
        }
        return choose(ranked);
    }

    public List<LayoutCandidate> layoutCandidates(String version, Cancellation token) {
        if (listsBuild(version, token)) {
            return List.of();
        }
        var build = BuildVersion.parse(version);
        Map<String, List<Ranked>> index = new TreeMap<>();
        for (var block : blocks) {
            token.check();
            Long distance = distance(block, build);
            if (distance != null) {
                for (var layout : block.layoutHashes()) {
                    index.computeIfAbsent(layout, k -> new ArrayList<>())
                            .add(new Ranked(block, distance));
                }
            }
        }
        var result = new ArrayList<LayoutCandidate>();
        for (var item : index.entrySet()) {
            token.check();
            var choice = choose(item.getValue());
            if (choice.blocks().size() == 1) {
                result.add(new LayoutCandidate(item.getKey(), choice.blocks().getFirst()));
            }
        }
        return List.copyOf(result);
    }

    private record Ranked(Block block, long distance) {}

    private static Long distance(Block block, BuildVersion build) {
        Long best = null;
        for (var range : block.builds()) {
            for (var bound : List.of(range.first(), range.last())) {
                if (bound.major() == build.major()) {
                    long gap = Math.abs(bound.build() - build.build());
                    if (best == null || gap < best) {
                        best = gap;
                    }
                }
            }
        }
        return best;
    }

    private static Selection choose(List<Ranked> ranked) {
        if (ranked.isEmpty()) {
            return new Selection("missing", List.of(), "layout");
        }
        long nearest = ranked.stream().mapToLong(Ranked::distance).min().orElseThrow();
        var tied = ranked.stream().filter(r -> r.distance() == nearest).map(Ranked::block).toList();
        boolean same = tied.stream().allMatch(b -> b.fields().equals(tied.getFirst().fields()));
        return new Selection(
                same ? "candidate" : "ambiguous", same ? List.of(tied.getFirst()) : tied, "layout");
    }
}
