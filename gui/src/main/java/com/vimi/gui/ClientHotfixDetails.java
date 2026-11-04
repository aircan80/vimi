package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.HotfixViewInfo;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Metadata-only origin labels; no payload, key material or provider error text. */
final class ClientHotfixDetails {
    private ClientHotfixDetails() {}

    static String failure(Throwable error) {
        for (int i = 0; i < 8 && error != null
                && (error.getClass() == java.util.concurrent.CompletionException.class
                        || error.getClass() == java.io.UncheckedIOException.class)
                && error.getCause() != null; i++) error = error.getCause();
        if (error instanceof com.vibeminer.formats.hotfix.HotfixFormatException format) {
            return switch (format.code()) {
                case "HOTFIX_BUILD_MISMATCH" -> "The cache belongs to a different client build.";
                case "HOTFIX_UNSUPPORTED" -> "The cache uses an unsupported format or record profile.";
                case "HOTFIX_LIMIT" -> "The cache exceeds the supported size or record limits.";
                case "HOTFIX_SOURCE_CHANGED" -> "The cache changed while it was being read; import it again.";
                default -> "The cache could not be verified.";
            };
        }
        return error != null && error.getClass() == java.util.concurrent.CancellationException.class
                ? "The operation was canceled." : "The local cache could not be read or verified.";
    }

    static CompletableFuture<String> row(BuildTables tables, String table, long id,
            String label, String version, Cancellation token) {
        return tables.hotfixInfo(TableTypes.Ref.forName(table), id,
                TableTypes.Priority.INTERACTIVE, token).thenApply(info -> label(info, label, version));
    }

    private static String label(Optional<HotfixViewInfo> info, String label, String version) {
        if (info.isEmpty() || info.get().row() == null) return label + ": exact source origin not retained";
        var source = info.get().row();
        String origin = switch (source.origin()) {
            case BASE -> "base DB2 source";
            case HOTFIX -> "local hotfix cache, build " + version;
            case DELETED -> "removed by local hotfix cache, build " + version;
            case NOT_PUBLIC -> "hidden by local hotfix cache, build " + version;
            case UNRESOLVED, UNKNOWN -> "client-cache source unresolved; no stale base substitution";
        };
        return label + ": " + origin;
    }

    static CompletableFuture<List<String>> talent(BuildTables tables,
            com.vibeminer.concepts.talents.TalentTypes.Talent talent,
            String version, Cancellation token) {
        long spell = talent.rankSpells().getFirst();
        var origin = talent.origin();
        var records = new java.util.ArrayList<CompletableFuture<String>>();
        if (origin.model() == com.vibeminer.concepts.talents.TalentTypes.Model.TRAIT_TREE) {
            records.add(row(tables, "TraitNode", talent.id(), "Trait node record", version, token));
            if (origin.traitNodeEntryId() != null)
                records.add(row(tables, "TraitNodeEntry", origin.traitNodeEntryId(), "Trait entry record", version, token));
            if (origin.traitDefinitionId() != null)
                records.add(row(tables, "TraitDefinition", origin.traitDefinitionId(), "Trait definition record", version, token));
        } else {
            records.add(row(tables, "Talent", talent.id(), "Tree record", version, token));
        }
        records.add(row(tables, "SpellName", spell, "Name", version, token));
        records.add(row(tables, "Spell", spell, "Description", version, token));
        records.add(icon(tables, spell, talent.iconFileDataId(), version, token));
        return CompletableFuture.allOf(records.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    var lines = new java.util.ArrayList<String>();
                    for (var record : records) lines.add(record.join());
                    lines.add("Local client caches may be incomplete. Cache absence does not prove server absence.");
                    return List.copyOf(lines);
                });
    }

    private static CompletableFuture<String> icon(BuildTables tables, long spell, Long icon,
            String version, Cancellation token) {
        if (icon == null) return CompletableFuture.completedFuture("Icon: no usable reference; see source notices");
        var ref = TableTypes.Ref.forName("SpellMisc");
        return tables.hotfixInfo(ref, null, TableTypes.Priority.INTERACTIVE, token)
                .thenCompose(info -> {
                    if (info.isEmpty()) return CompletableFuture.completedFuture("Icon relationship: exact source origin not retained");
                    var filters = List.of(
                            new TableTypes.Filter("SpellID", TableTypes.FilterOp.EQUALS, Long.toString(spell), null),
                            new TableTypes.Filter("DifficultyID", TableTypes.FilterOp.EQUALS, "0", null),
                            new TableTypes.Filter("SpellIconFileDataID", TableTypes.FilterOp.EQUALS, Long.toString(icon), null));
                    return tables.page(new TableTypes.PageRequest(ref, -1, 2, filters, null),
                                    TableTypes.Priority.INTERACTIVE, token)
                            .thenCompose(page -> {
                                if (page.state() != TableTypes.PageState.OK || page.rows().size() != 1
                                        || page.nextAfter() != null) return CompletableFuture.completedFuture(
                                                "Icon relationship: exact row origin could not be confirmed");
                                return row(tables, "SpellMisc", page.rows().getFirst().row().id(),
                                        "Icon relationship", version, token);
                            });
                });
    }
}
