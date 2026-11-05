package com.vibeminer.concepts.talents;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.TalentsService.Data;
import com.vibeminer.concepts.talents.TalentsService.Projection;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Forever's TraitTree talent source. One class TraitTree holds the three classic trees; its maximal
 * TraitNodeGroup member sets partition the nodes into those trees. Positions are placed on a
 * reviewed 600-unit grid. Owned and serialized by {@link TalentsService}'s single worker.
 */
final class TraitTrees {
    /**
     * Physical layouts whose unverified provider field names were checked against actual Forever
     * 1.60.1.70707 and 1.60.1.70755 client tables. Other layouts fail explicitly.
     */
    static final Map<String, String> REVIEWED_LAYOUTS =
            Map.of(
                    "SkillLineXTraitTree", "853B88D6",
                    "TraitNode", "A01A518A",
                    "TraitNodeEntry", "733B015D",
                    "TraitDefinition", "060A9F4C",
                    "TraitEdge", "907C9B77",
                    "TraitNodeXTraitNodeEntry", "802ED455",
                    "TraitNodeGroup", "ECA626BE",
                    "TraitNodeGroupXTraitNode", "B9288EB8");

    static final int GRID = 600,
            TOLERANCE = 60,
            COLUMNS = 4,
            MAX_ROWS = 11,
            MAX_TREES = 3,
            MAX_RANKS = 100,
            MAX_ROWS_PER_TABLE = 50000;

    private static final List<String> GRAPH_TABLES =
            List.of(
                    "TraitNode",
                    "TraitNodeXTraitNodeEntry",
                    "TraitNodeGroup",
                    "TraitNodeGroupXTraitNode",
                    "TraitEdge");

    private final TalentsService owner;
    private Boolean selected;
    private boolean loaded;
    private Data classes, nodes, nodeEntries, groups, groupNodes, edges, entries, definitions;
    private final Map<Long, Long> classTrees = new TreeMap<>();
    private final List<String> issues = new ArrayList<>();
    private Map<Long, Set<Long>> legacyTabSpells;
    private Data legacyTabs;

    TraitTrees(TalentsService owner) {
        this.owner = owner;
    }

    /** Forever products and betas. Other Classic products always use the legacy reader. */
    static boolean foreverFamily(String product, String version) {
        return product != null
                && (product.equals("wowf")
                        || product.equals("wow_classic_beta")
                        || product.equals("wow_cn_beta"))
                && version != null
                && version.startsWith("1.60.");
    }

    /**
     * True when this Forever build records SkillLineXTraitTree. A build without the table uses the
     * legacy reader; an unreadable table is an explicit failure, never a legacy substitution.
     */
    boolean selected(String product, String version, Cancellation token) {
        if (selected != null) {
            return selected;
        }
        if (!foreverFamily(product, version)) {
            selected = false;
            return false;
        }
        var probe = owner.probe("SkillLineXTraitTree", token);
        var state = probe.table().state();
        // A file absent from the build, or a name unknown to the definition manifest (ID 0), means
        // this build predates TraitTree talents. A known but undecodable table is a failure.
        if (state == TableTypes.State.NOT_IN_BUILD
                || state == TableTypes.State.NO_DEFINITION && probe.table().fileDataId() == 0) {
            selected = false;
            return false;
        }
        if (state != TableTypes.State.READY && state != TableTypes.State.PARTIAL) {
            throw new Projection(
                    TalentTypes.State.UNAVAILABLE,
                    "TALENTS_TRAIT_TABLE_" + state,
                    "SkillLineXTraitTree: "
                            + state
                            + (probe.table().reason() == null
                                    ? ""
                                    : " (" + probe.table().reason().code() + ")")
                            + "; legacy Talent rows are not substituted for TraitTree data.");
        }
        selected = true;
        return true;
    }

    TalentTypes.Catalog catalog(Cancellation token) {
        load(token);
        var result = new ArrayList<TalentTypes.PlayerClass>();
        for (var row : classes.rows) {
            if (classTrees.containsKey(row.id())) {
                result.add(TalentsService.playerClass(classes, row));
            }
        }
        boolean partial = partial();
        String message =
                !issues.isEmpty()
                        ? String.join(" · ", issues)
                        : result.isEmpty()
                                ? "No player-class TraitTree talents are recorded in this build."
                                : null;
        return new TalentTypes.Catalog(
                partial ? TalentTypes.State.PARTIAL : TalentTypes.State.READY,
                result,
                partial ? "TALENTS_PARTIAL" : null,
                message);
    }

    TalentTypes.ClassTrees trees(long classId, Cancellation token) {
        load(token);
        Long treeId = classTrees.get(classId);
        var classRow = classes.rows.stream().filter(r -> r.id() == classId).findFirst();
        if (treeId == null || classRow.isEmpty()) {
            return TalentsService.failed(
                    TalentTypes.State.UNAVAILABLE,
                    "TALENTS_CLASS_UNAVAILABLE",
                    "This class has no recorded TraitTree talents.");
        }
        var player = TalentsService.playerClass(classes, classRow.get());
        var classIssues = new ArrayList<>(issues);
        boolean graphPartial = GRAPH_TABLES.stream().anyMatch(owner.partialTables::contains);

        var treeNodes = new LinkedHashMap<Long, Db2Types.Row>();
        for (var row : nodes.rows) {
            token.check();
            if (nodes.number(row, "TraitTreeID") == treeId) {
                treeNodes.put(row.id(), row);
                if (treeNodes.size() > TalentsService.MAX_CLASS_TALENTS) {
                    throw TalentsService.invalid("Class talent count exceeds the supported bound");
                }
            }
        }
        var partition = partition(treeId, treeNodes.keySet(), graphPartial, classIssues);
        if (partition == null) {
            return new TalentTypes.ClassTrees(
                    TalentTypes.State.UNSUPPORTED,
                    player,
                    List.of(),
                    List.of(),
                    "TALENTS_TRAIT_PARTITION_UNSUPPORTED",
                    "TraitTree "
                            + treeId
                            + " node groups do not form "
                            + MAX_TREES
                            + " disjoint talent trees; no layout was inferred.",
                    TalentTypes.Model.TRAIT_TREE);
        }

        var entryLinks = new HashMap<Long, List<Db2Types.Row>>();
        for (var link : nodeEntries.rows) {
            entryLinks
                    .computeIfAbsent(
                            nodeEntries.number(link, "TraitNodeID"), k -> new ArrayList<>())
                    .add(link);
        }
        var built = new LinkedHashMap<Long, Built>();
        var unavailable = new TreeMap<String, Integer>();
        for (var node : treeNodes.values()) {
            token.check();
            var made = build(treeId, node, entryLinks.get(node.id()), unavailable, token);
            if (made != null) {
                built.put(node.id(), made);
            }
        }
        unavailable.forEach(
                (why, count) ->
                        classIssues.add(count + " TraitTree talent node(s) are not shown: " + why));

        var wanted = new HashSet<Long>();
        for (var made : built.values()) {
            if (made.overrideIcon == null) {
                wanted.add(made.spell);
            }
        }
        owner.optionalMetadata(token, classIssues, wanted);
        var prerequisites = prerequisites(treeNodes, built.keySet(), classIssues);

        var finished = new HashMap<Long, TalentTypes.Talent>();
        for (var made : built.values()) {
            String name = made.overrideName;
            if (name == null) {
                name =
                        owner.optionalText(
                                owner.spellNames, made.spell, "Name_lang", token, classIssues);
                if (name == null || name.isEmpty()) {
                    name = "Spell " + made.spell;
                }
            }
            String description = made.overrideDescription;
            if (description == null) {
                description =
                        owner.optionalText(
                                owner.spellDescriptions,
                                made.spell,
                                "Description_lang",
                                token,
                                classIssues);
            }
            Long icon = made.overrideIcon != null ? made.overrideIcon : owner.icon(made.spell);
            finished.put(
                    made.nodeId,
                    new TalentTypes.Talent(
                            made.nodeId,
                            -1,
                            -1,
                            List.of(made.spell),
                            made.maxRanks,
                            prerequisites.getOrDefault(made.nodeId, List.of()),
                            name,
                            description,
                            icon,
                            new TalentTypes.Origin(
                                    TalentTypes.Model.TRAIT_TREE,
                                    treeId,
                                    made.entryId,
                                    made.definitionId,
                                    made.x,
                                    made.y,
                                    made.overridesSpell,
                                    made.overrideName != null,
                                    made.overrideDescription != null),
                            icon == null ? owner.iconIssue(made.spell) : null));
        }
        var complete = new HashSet<Long>();
        for (var talent : finished.values()) {
            TalentsService.visit(talent, finished, new HashSet<>(), complete);
        }

        long originY = origin(built.values().stream().map(b -> b.y).toList(), MAX_ROWS);
        var trees = new ArrayList<Placed>();
        for (var group : partition.entrySet()) {
            var members = group.getValue().stream().filter(built::containsKey).toList();
            long originX = origin(members.stream().map(id -> built.get(id).x).toList(), COLUMNS);
            trees.add(new Placed(group.getKey(), originX, members));
        }
        trees.sort(Comparator.comparingLong(Placed::originX).thenComparingLong(Placed::groupId));
        var names = names(classId, trees, built, classIssues, token);

        var result = new ArrayList<TalentTypes.Tree>();
        for (int order = 0; order < trees.size(); order++) {
            var placed = trees.get(order);
            var cells = new TreeMap<String, List<Built>>();
            var unplaced = new ArrayList<TalentTypes.UnplacedTalent>();
            for (long id : placed.members) {
                var made = built.get(id);
                Integer column = cell(made.x - placed.originX, COLUMNS);
                Integer row = cell(made.y - originY, MAX_ROWS);
                if (column == null || row == null) {
                    unplaced.add(
                            new TalentTypes.UnplacedTalent(
                                    finished.get(id),
                                    "Recorded position ("
                                            + made.x
                                            + ", "
                                            + made.y
                                            + ") is outside this tree's "
                                            + COLUMNS
                                            + "-column grid."));
                    continue;
                }
                made.row = row;
                made.column = column;
                cells.computeIfAbsent(row + ":" + column, k -> new ArrayList<>()).add(made);
            }
            var talents = new ArrayList<TalentTypes.Talent>();
            for (var occupants : cells.values()) {
                occupants.sort(
                        Comparator.comparingLong(
                                        (Built b) ->
                                                Math.abs(b.x - placed.originX - b.column * GRID)
                                                        + Math.abs(b.y - originY - b.row * GRID))
                                .thenComparingLong(b -> b.nodeId));
                for (int i = 0; i < occupants.size(); i++) {
                    var made = occupants.get(i);
                    var talent = finished.get(made.nodeId);
                    if (i == 0) {
                        talents.add(at(talent, made.row, made.column));
                    } else {
                        unplaced.add(
                                new TalentTypes.UnplacedTalent(
                                        talent,
                                        "Shares tier "
                                                + (made.row + 1)
                                                + ", column "
                                                + (made.column + 1)
                                                + " with TraitNode "
                                                + occupants.getFirst().nodeId
                                                + "."));
                    }
                }
            }
            talents.sort(
                    Comparator.comparingInt(TalentTypes.Talent::row)
                            .thenComparingInt(TalentTypes.Talent::column));
            unplaced.sort(Comparator.comparingLong(u -> u.talent().id()));
            if (!unplaced.isEmpty()) {
                classIssues.add(
                        unplaced.size()
                                + " readable talent(s) in "
                                + names.get(order).name
                                + " could not be placed on the grid; see the tree's unplaced list.");
            }
            var name = names.get(order);
            result.add(
                    new TalentTypes.Tree(
                            placed.groupId, name.name, order, name.background, talents, unplaced));
        }
        if (result.size() < MAX_TREES) {
            classIssues.add(
                    "Only "
                            + result.size()
                            + " talent trees are readable; additional trees may be unavailable in partial tables.");
        }
        return new TalentTypes.ClassTrees(
                partial() ? TalentTypes.State.PARTIAL : TalentTypes.State.READY,
                player,
                result,
                dedupe(classIssues),
                null,
                null,
                TalentTypes.Model.TRAIT_TREE);
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new java.util.LinkedHashSet<>(values));
    }

    private boolean partial() {
        return !owner.partialTables.isEmpty();
    }

    private static TalentTypes.Talent at(TalentTypes.Talent talent, int row, int column) {
        return new TalentTypes.Talent(
                talent.id(),
                row,
                column,
                talent.rankSpells(),
                talent.maxRanks(),
                talent.prerequisites(),
                talent.name(),
                talent.description(),
                talent.iconFileDataId(),
                talent.origin(),
                talent.iconIssue());
    }

    /** Grid index for an offset within tolerance of a 600-unit cell, or null. */
    static Integer cell(long offset, int count) {
        long index = Math.round(offset / (double) GRID);
        if (index < 0 || index >= count || Math.abs(offset - index * GRID) > TOLERANCE) {
            return null;
        }
        return (int) index;
    }

    /**
     * The grid anchor for the recorded coordinate that places the most values within {@code count}
     * cells (ties choose the smaller one). Outlying parked nodes cannot shift the grid.
     */
    static long origin(List<Long> values, int count) {
        long best = 0;
        int bestCount = -1;
        for (long candidate : new java.util.TreeSet<>(values)) {
            int placed = 0;
            for (long value : values) {
                if (cell(value - candidate, count) != null) {
                    placed++;
                }
            }
            if (placed > bestCount) {
                best = candidate;
                bestCount = placed;
            }
        }
        // Refine to the most common exact grid anchor so a slightly nudged node cannot shift the
        // cells of the exactly aligned majority. Ties choose the smaller anchor.
        var anchors = new TreeMap<Long, Integer>();
        for (long value : values) {
            Integer index = cell(value - best, count);
            if (index != null) {
                anchors.merge(value - (long) index * GRID, 1, Integer::sum);
            }
        }
        long anchor = best;
        int anchorCount = 0;
        for (var entry : anchors.entrySet()) {
            if (entry.getValue() > anchorCount) {
                anchor = entry.getKey();
                anchorCount = entry.getValue();
            }
        }
        return anchor;
    }

    private record Placed(long groupId, long originX, List<Long> members) {}

    private record TreeName(String name, String background) {}

    private static final class Built {
        long nodeId, x, y, spell;
        Long entryId, definitionId, overrideIcon, overridesSpell;
        int maxRanks, row, column;
        String overrideName, overrideDescription;
    }

    private Built build(
            long treeId,
            Db2Types.Row node,
            List<Db2Types.Row> links,
            Map<String, Integer> unavailable,
            Cancellation token) {
        var made = new Built();
        made.nodeId = node.id();
        made.x = nodes.number(node, "PosX");
        made.y = nodes.number(node, "PosY");
        long type = nodes.number(node, "Type");
        int count = links == null ? 0 : links.size();
        if (type != 0 || count != 1) {
            unavailable.merge(
                    count == 0
                            ? "no readable TraitNodeXTraitNodeEntry link"
                            : "choice or tiered nodes (TraitNode Type "
                                    + type
                                    + ", "
                                    + count
                                    + " entries) are not supported",
                    1,
                    Integer::sum);
            return null;
        }
        long entryId = nodeEntries.number(links.getFirst(), "TraitNodeEntryID");
        var entry = owner.get("TraitNodeEntry", entryId, token);
        if (entry.outcome() != TableTypes.RowOutcome.FOUND || entry.row() == null) {
            unavailable.merge("TraitNodeEntry rows are " + entry.outcome(), 1, Integer::sum);
            return null;
        }
        var entryRow = entry.row().row();
        long definitionId = entries.number(entryRow, "TraitDefinitionID");
        long ranks = entries.number(entryRow, "MaxRanks");
        if (ranks < 1 || ranks > MAX_RANKS) {
            unavailable.merge("TraitNodeEntry MaxRanks is outside 1–" + MAX_RANKS, 1, Integer::sum);
            return null;
        }
        var definition = owner.get("TraitDefinition", definitionId, token);
        if (definition.outcome() != TableTypes.RowOutcome.FOUND || definition.row() == null) {
            unavailable.merge("TraitDefinition rows are " + definition.outcome(), 1, Integer::sum);
            return null;
        }
        var definitionRow = definition.row().row();
        long spell = definitions.number(definitionRow, "SpellID");
        if (spell <= 0 || spell > 0xffffffffL) {
            unavailable.merge("TraitDefinition records no spell", 1, Integer::sum);
            return null;
        }
        made.entryId = entryId;
        made.definitionId = definitionId;
        made.maxRanks = (int) ranks;
        made.spell = spell;
        made.overrideName = nonEmpty(definitions, definitionRow, "OverrideName_lang");
        made.overrideDescription = nonEmpty(definitions, definitionRow, "OverrideDescription_lang");
        long icon = definitions.number(definitionRow, "OverrideIcon");
        made.overrideIcon = icon > 0 && icon <= 0xffffffffL ? icon : null;
        long overrides = definitions.number(definitionRow, "OverridesSpellID");
        made.overridesSpell = overrides > 0 ? overrides : null;
        return made;
    }

    private static String nonEmpty(Data data, Db2Types.Row row, String field) {
        try {
            String value = data.text(row, field);
            return value == null || value.isBlank() ? null : value;
        } catch (Projection malformed) {
            return null;
        }
    }

    /**
     * Groups whose readable member sets are not contained in another group's. Returns null when
     * they overlap or exceed three trees; nodes outside every group are reported.
     */
    private Map<Long, List<Long>> partition(
            long treeId, Set<Long> treeNodes, boolean partialGraph, List<String> classIssues) {
        var groupIds = new HashSet<Long>();
        for (var row : groups.rows) {
            if (groups.number(row, "TraitTreeID") == treeId) {
                groupIds.add(row.id());
            }
        }
        var members = new TreeMap<Long, Set<Long>>();
        for (var link : groupNodes.rows) {
            long group = groupNodes.number(link, "TraitNodeGroupID");
            long node = groupNodes.number(link, "TraitNodeID");
            if (groupIds.contains(group) && treeNodes.contains(node)) {
                members.computeIfAbsent(group, k -> new HashSet<>()).add(node);
            }
        }
        var maximal = new LinkedHashMap<Set<Long>, Long>();
        for (var group : members.entrySet()) {
            var set = group.getValue();
            boolean contained =
                    members.values().stream()
                            .anyMatch(other -> other.size() > set.size() && other.containsAll(set));
            if (!contained) {
                maximal.putIfAbsent(set, group.getKey());
            }
        }
        var covered = new HashSet<Long>();
        for (var set : maximal.keySet()) {
            for (long node : set) {
                if (!covered.add(node)) {
                    return null;
                }
            }
        }
        if (maximal.size() > MAX_TREES || maximal.size() < MAX_TREES && !partialGraph) {
            return null;
        }
        int outside = treeNodes.size() - covered.size();
        if (outside > 0) {
            if (!partialGraph) {
                return null;
            }
            classIssues.add(
                    outside
                            + " TraitNode row(s) have no readable tree group in the partial tables and are not shown.");
        }
        var result = new LinkedHashMap<Long, List<Long>>();
        maximal.forEach((set, group) -> result.put(group, set.stream().sorted().toList()));
        return result;
    }

    /** TraitEdge types 2 and 3 are prerequisites; type 0 is visual only and is not drawn. */
    private Map<Long, List<TalentTypes.Prerequisite>> prerequisites(
            Map<Long, Db2Types.Row> treeNodes, Set<Long> readable, List<String> classIssues) {
        var byTarget = new HashMap<Long, Map<Long, TalentTypes.PrerequisiteKind>>();
        for (var edge : edges.rows) {
            long left = edges.number(edge, "LeftTraitNodeID");
            long right = edges.number(edge, "RightTraitNodeID");
            if (!readable.contains(right)) {
                continue;
            }
            long type = edges.number(edge, "Type");
            TalentTypes.PrerequisiteKind kind;
            if (type == 0) {
                continue;
            } else if (type == 2) {
                kind = TalentTypes.PrerequisiteKind.TRAIT_SUFFICIENT;
            } else if (type == 3) {
                kind = TalentTypes.PrerequisiteKind.TRAIT_REQUIRED;
            } else {
                classIssues.add(
                        "TraitEdge type " + type + " is not supported; such links are omitted.");
                continue;
            }
            if (left == right) {
                throw TalentsService.invalid("TraitEdge links a node to itself");
            }
            if (!treeNodes.containsKey(left)) {
                classIssues.add(
                        "Some TraitEdge prerequisites refer to nodes outside this class tree; they are listed without arrows.");
            } else if (!readable.contains(left)) {
                classIssues.add(
                        "Some TraitEdge prerequisites refer to talents that are not shown; their arrows are omitted.");
            }
            byTarget.computeIfAbsent(right, k -> new TreeMap<>())
                    .merge(
                            left,
                            kind,
                            (a, b) -> a == TalentTypes.PrerequisiteKind.TRAIT_REQUIRED ? a : b);
        }
        // Actual Forever data links some pairs in both directions. Keep only the link whose
        // prerequisite is recorded above its dependent; drop both when they share a row.
        for (var target : new ArrayList<>(byTarget.keySet())) {
            for (long source : new ArrayList<>(byTarget.get(target).keySet())) {
                var reverse = byTarget.get(source);
                if (target < source || reverse == null || !reverse.containsKey(target)) {
                    continue;
                }
                long targetY = nodes.number(treeNodes.get(target), "PosY");
                long sourceY = nodes.number(treeNodes.get(source), "PosY");
                if (sourceY >= targetY) {
                    byTarget.get(target).remove(source);
                }
                if (targetY >= sourceY) {
                    reverse.remove(target);
                }
                classIssues.add(
                        "Some TraitEdge pairs link two talents in both directions; only a link from"
                                + " the higher talent to the lower one is shown.");
            }
        }
        var result = new HashMap<Long, List<TalentTypes.Prerequisite>>();
        byTarget.forEach(
                (target, sources) -> {
                    var list = new ArrayList<TalentTypes.Prerequisite>();
                    sources.forEach(
                            (source, kind) ->
                                    list.add(new TalentTypes.Prerequisite(source, null, kind)));
                    result.put(target, list);
                });
        return result;
    }

    /**
     * TraitTree rows record no tree titles. A legacy TalentTab name is used only when the trees and
     * tabs match one-to-one by their strictly largest shared spell count.
     */
    private List<TreeName> names(
            long classId,
            List<Placed> trees,
            Map<Long, Built> built,
            List<String> classIssues,
            Cancellation token) {
        var neutral = new ArrayList<TreeName>();
        for (int i = 0; i < trees.size(); i++) {
            neutral.add(new TreeName("Talent tree " + (i + 1), ""));
        }
        String namingFailure = legacyNaming(token);
        if (legacyTabs == null) {
            classIssues.add(
                    "Tree names are unavailable: "
                            + namingFailure
                            + " Neutral names are shown; Reload to retry.");
            return neutral;
        }
        var tabs = new ArrayList<Db2Types.Row>();
        for (var tab : legacyTabs.rows) {
            if ((TalentsService.mask(legacyTabs.number(tab, "ClassMask")) & (1L << (classId - 1)))
                    != 0) {
                tabs.add(tab);
            }
        }
        var chosen = new ArrayList<TreeName>();
        var used = new HashSet<Long>();
        for (var tree : trees) {
            var spells = new HashSet<Long>();
            for (long id : tree.members) {
                spells.add(built.get(id).spell);
            }
            Db2Types.Row best = null;
            int bestCount = 0;
            boolean tie = false;
            for (var tab : tabs) {
                var shared = new HashSet<>(legacyTabSpells.getOrDefault(tab.id(), Set.of()));
                shared.retainAll(spells);
                if (shared.size() > bestCount) {
                    best = tab;
                    bestCount = shared.size();
                    tie = false;
                } else if (shared.size() == bestCount && bestCount > 0) {
                    tie = true;
                }
            }
            if (best == null || tie || !used.add(best.id())) {
                classIssues.add(
                        "TraitTree data records no tree names and the legacy TalentTab names could not be matched one-to-one by shared spells; neutral names are shown.");
                return neutral;
            }
            String name = nonEmpty(legacyTabs, best, "Name_lang");
            String background = nonEmpty(legacyTabs, best, "BackgroundFile");
            chosen.add(
                    new TreeName(
                            name == null ? "Talent tree " + (chosen.size() + 1) : name,
                            background == null ? "" : background));
        }
        return chosen;
    }

    /**
     * Reads the legacy tab names once. A failure is returned, not remembered, so the next request
     * reads the tables again.
     */
    private String legacyNaming(Cancellation token) {
        if (legacyTabs != null) {
            return null;
        }
        var ignored = new ArrayList<String>();
        try {
            var tabs = owner.read("TalentTab", TalentsService.MAX_TABS, token, ignored);
            tabs.textField("Name_lang");
            tabs.textField("BackgroundFile");
            tabs.integerField("ClassMask", 1);
            var talentTable = owner.schema("Talent", token, ignored);
            talentTable.integerField("TabID", 1);
            talentTable.integerField("SpellRank", 9);
            var spells = new HashMap<Long, Set<Long>>();
            owner.stream(
                    talentTable,
                    TalentsService.MAX_TALENTS,
                    token,
                    row -> {
                        var set =
                                spells.computeIfAbsent(
                                        talentTable.number(row, "TabID"), k -> new HashSet<>());
                        for (long spell : talentTable.array(row, "SpellRank", 9)) {
                            if (spell != 0) {
                                set.add(spell);
                            }
                        }
                    });
            legacyTabSpells = spells;
            legacyTabs = tabs;
            return null;
        } catch (RuntimeException failure) {
            return TalentsService.cause(failure) + ".";
        } finally {
            // Legacy tables only name trees; their partial state does not qualify trait data.
            owner.partialTables.remove("TalentTab");
            owner.partialTables.remove("Talent");
        }
    }

    private void load(Cancellation token) {
        if (loaded) {
            return;
        }
        var loadIssues = new ArrayList<String>();
        var newClasses = owner.read("ChrClasses", TalentsService.MAX_CLASSES, token, loadIssues);
        newClasses.textField("Name_lang");
        var skillTrees = reviewed(owner.read("SkillLineXTraitTree", 4096, token, loadIssues));
        for (String field : List.of("SkillLineID", "TraitTreeID", "Variant")) {
            skillTrees.integerField(field, 1);
        }
        var raceClass = owner.read("SkillRaceClassInfo", MAX_ROWS_PER_TABLE, token, loadIssues);
        raceClass.integerField("SkillID", 1);
        raceClass.integerField("ClassMask", 1);
        var masks = new HashMap<Long, Long>();
        for (var row : raceClass.rows) {
            masks.merge(
                    raceClass.number(row, "SkillID"),
                    TalentsService.mask(raceClass.number(row, "ClassMask")),
                    (a, b) -> a | b);
        }
        var mapped = new TreeMap<Long, Long>();
        var conflicted = new HashSet<Long>();
        for (var row : skillTrees.rows) {
            long skill = skillTrees.number(row, "SkillLineID");
            long tree = skillTrees.number(row, "TraitTreeID");
            long variant = skillTrees.number(row, "Variant");
            if (variant != 0) {
                loadIssues.add("SkillLineXTraitTree rows with an unreviewed Variant are ignored.");
                continue;
            }
            long mask = masks.getOrDefault(skill, 0L);
            if (Long.bitCount(mask) != 1) {
                loadIssues.add(
                        "SkillLineXTraitTree rows whose skill line has no single player class are ignored.");
                continue;
            }
            long classId = Long.numberOfTrailingZeros(mask) + 1;
            Long previous = mapped.putIfAbsent(classId, tree);
            if (previous != null && previous != tree) {
                conflicted.add(classId);
            }
        }
        for (long classId : conflicted) {
            mapped.remove(classId);
            loadIssues.add(
                    "Class "
                            + classId
                            + " maps to several TraitTrees; its talents are not inferred.");
        }
        var treeIds = new HashSet<>(mapped.values());

        var newNodes = reviewed(owner.schema("TraitNode", token, loadIssues));
        for (String field : List.of("TraitTreeID", "PosX", "PosY", "Type")) {
            newNodes.integerField(field, 1);
        }
        final var nodeTable = newNodes;
        newNodes =
                retain(
                        nodeTable,
                        token,
                        row -> treeIds.contains(nodeTable.number(row, "TraitTreeID")));
        var nodeIds = new HashSet<Long>();
        var populatedTrees = new HashSet<Long>();
        for (var row : newNodes.rows) {
            nodeIds.add(row.id());
            populatedTrees.add(newNodes.number(row, "TraitTreeID"));
        }
        var newLinks = reviewed(owner.schema("TraitNodeXTraitNodeEntry", token, loadIssues));
        newLinks.integerField("TraitNodeID", 1);
        newLinks.integerField("TraitNodeEntryID", 1);
        final var links = newLinks;
        newLinks = retain(links, token, row -> nodeIds.contains(links.number(row, "TraitNodeID")));
        var newGroups = reviewed(owner.schema("TraitNodeGroup", token, loadIssues));
        newGroups.integerField("TraitTreeID", 1);
        final var groupTable = newGroups;
        newGroups =
                retain(
                        groupTable,
                        token,
                        row -> treeIds.contains(groupTable.number(row, "TraitTreeID")));
        var groupIds = new HashSet<Long>();
        for (var row : newGroups.rows) {
            groupIds.add(row.id());
        }
        var newGroupNodes = reviewed(owner.schema("TraitNodeGroupXTraitNode", token, loadIssues));
        newGroupNodes.integerField("TraitNodeGroupID", 1);
        newGroupNodes.integerField("TraitNodeID", 1);
        final var groupLinks = newGroupNodes;
        newGroupNodes =
                retain(
                        groupLinks,
                        token,
                        row -> groupIds.contains(groupLinks.number(row, "TraitNodeGroupID")));
        var newEdges = reviewed(owner.schema("TraitEdge", token, loadIssues));
        for (String field : List.of("LeftTraitNodeID", "RightTraitNodeID", "Type")) {
            newEdges.integerField(field, 1);
        }
        final var edgeTable = newEdges;
        newEdges =
                retain(
                        edgeTable,
                        token,
                        row ->
                                nodeIds.contains(edgeTable.number(row, "LeftTraitNodeID"))
                                        || nodeIds.contains(
                                                edgeTable.number(row, "RightTraitNodeID")));
        var newEntries = reviewed(owner.schema("TraitNodeEntry", token, loadIssues));
        newEntries.integerField("TraitDefinitionID", 1);
        newEntries.integerField("MaxRanks", 1);
        var newDefinitions = reviewed(owner.schema("TraitDefinition", token, loadIssues));
        for (String field : List.of("SpellID", "OverrideIcon", "OverridesSpellID")) {
            newDefinitions.integerField(field, 1);
        }
        newDefinitions.textField("OverrideName_lang");
        newDefinitions.textField("OverrideDescription_lang");

        mapped.keySet().removeIf(classId -> !populatedTrees.contains(mapped.get(classId)));
        classes = newClasses;
        nodes = newNodes;
        nodeEntries = newLinks;
        groups = newGroups;
        groupNodes = newGroupNodes;
        edges = newEdges;
        entries = newEntries;
        definitions = newDefinitions;
        classTrees.putAll(mapped);
        issues.addAll(dedupe(loadIssues));
        loaded = true;
    }

    private Data retain(
            Data table, Cancellation token, java.util.function.Predicate<Db2Types.Row> keep) {
        var rows = new ArrayList<Db2Types.Row>();
        owner.stream(
                table,
                MAX_ROWS_PER_TABLE,
                token,
                row -> {
                    if (keep.test(row)) {
                        rows.add(row);
                    }
                });
        return table.withRows(rows);
    }

    private static Data reviewed(Data data) {
        if (!data.reviewedLayout) {
            throw new Projection(
                    TalentTypes.State.UNSUPPORTED,
                    "TALENTS_TRAIT_LAYOUT_UNREVIEWED",
                    data.name + ": this physical layout is not in the reviewed TraitTree profile.");
        }
        return data;
    }
}
