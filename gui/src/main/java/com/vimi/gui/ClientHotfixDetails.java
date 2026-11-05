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

    /** Fixed-vocabulary refresh summary; never includes paths, payloads or provider text. */
    static String refreshSummary(List<ClientHotfixStore.RefreshResult> results) {
        if (results.isEmpty()) return "No client caches are marked for checking";
        int updated = 0, unchanged = 0, moved = 0, missing = 0, failed = 0;
        for (var result : results) {
            switch (result.outcome()) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case CLIENT_BUILD_CHANGED -> moved++;
                case MISSING -> missing++;
                case FAILED -> failed++;
            }
        }
        var parts = new java.util.ArrayList<String>();
        if (updated > 0) parts.add(updated + " newer cache" + (updated == 1 ? "" : "s")
                + " imported; reopen the build to apply");
        if (unchanged > 0) parts.add(unchanged + " unchanged");
        if (moved > 0) parts.add(moved + " now for another client build; existing import kept");
        if (missing > 0) parts.add(missing + " not found; existing import kept");
        if (failed > 0) parts.add(failed + " could not be verified; existing import kept");
        return String.join(" · ", parts);
    }

    /** A confirmed move of a watched cache into the build its client now runs. */
    record BuildFollow(
            ClientHotfixStore.Scope from, ClientHotfixStore.Scope to, int build, String prompt) {
        String key() {
            return from.key() + ">" + to.key();
        }
    }

    /**
     * The prompt for a watched cache whose client moved to another build, when exactly one
     * prepared build of the same product and locale has that build number (same region preferred).
     * Empty when nothing matches or the choice would be a guess; the user is never switched silently.
     */
    static java.util.Optional<BuildFollow> buildFollow(ClientHotfixStore.RefreshResult result,
            List<BuildInfo> builds, List<PreparationHistory.Receipt> receipts) {
        if (result.outcome() != ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED
                || result.scope() == null || result.clientBuild() <= 0) return java.util.Optional.empty();
        var from = result.scope();
        int build = result.clientBuild();
        var candidates = new java.util.ArrayList<ClientHotfixStore.Scope>();
        for (var receipt : receipts) {
            if (receipt.milestone() != BuildPreparation.Ready.TABLES_READY
                    || !receipt.product().equals(from.build().product())
                    || !receipt.locale().equals(from.locale())
                    || receipt.cdnConfig() == null || receipt.region() == null) continue;
            for (var info : builds) {
                if (!info.identity().equals(receipt.identity()) || !info.config().equals(receipt.config())
                        || info.version() == null || !info.version().endsWith("." + build)) continue;
                try {
                    var to = new ClientHotfixStore.Scope(new com.vibeminer.builds.BuildTypes.BuildRef(
                            receipt.product(), receipt.config(), receipt.cdnConfig(), receipt.region()),
                            receipt.locale(), info.version());
                    if (to.buildNumber() == build && !to.equals(from) && !candidates.contains(to))
                        candidates.add(to);
                } catch (IllegalArgumentException notExact) {
                    // Only exact four-part versions can hold a client cache.
                }
            }
        }
        var sameRegion = candidates.stream()
                .filter(to -> java.util.Objects.equals(to.build().region(), from.build().region())).toList();
        var pick = sameRegion.size() == 1 ? sameRegion : candidates;
        if (pick.size() != 1) return java.util.Optional.empty();
        return java.util.Optional.of(new BuildFollow(from, pick.getFirst(), build,
                "Client updated to build " + build + ". Apply this cache to build " + build + "?"));
    }

    static boolean refreshFailed(List<ClientHotfixStore.RefreshResult> results) {
        return results.stream().anyMatch(result -> result.outcome() == ClientHotfixStore.RefreshOutcome.FAILED);
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
            case BASE -> info.get().unavailable() == null
                    ? "base DB2 source"
                    : HotfixViewInfo.TABLE_BUDGET.equals(info.get().unavailable())
                            ? "base DB2 source; local hotfix cache not applied to this table (too large)"
                            : "base DB2 source; hotfix overlay memory busy, showing base rows";
            case HOTFIX -> "local hotfix cache, build " + version;
            case DELETED -> "removed by local hotfix cache, build " + version;
            case NOT_PUBLIC -> "hidden by local hotfix cache, build " + version;
            // The client hides records its cache marks INVALID; say so rather than "unknown".
            case UNRESOLVED, UNKNOWN -> "INVALID".equals(source.state())
                    ? "hidden by local hotfix cache (INVALID), build " + version
                    : "client-cache source unresolved; no stale base substitution";
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
