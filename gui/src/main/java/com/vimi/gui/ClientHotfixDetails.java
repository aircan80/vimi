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
                case "HOTFIX_RECORD_LIMIT" -> "The cache holds " + format.records()
                        + " records; vimi reads at most " + format.recordLimit()
                        + ". Its hotfixes are not applied.";
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
        int updated = 0, unchanged = 0, moved = 0, missing = 0, failed = 0, superseded = 0;
        for (var result : results) {
            switch (result.outcome()) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case CLIENT_BUILD_CHANGED -> moved++;
                case MISSING -> missing++;
                case FAILED -> failed++;
                case SUPERSEDED -> superseded++;
            }
        }
        var parts = new java.util.ArrayList<String>();
        if (updated > 0) parts.add(updated + " newer cache" + (updated == 1 ? "" : "s")
                + " imported; reopen the build to apply");
        if (unchanged > 0) parts.add(unchanged + " unchanged");
        if (moved > 0) parts.add(moved + " now for another client build; existing import kept");
        if (missing > 0) parts.add(missing + " not found; existing import kept");
        if (failed > 0) parts.add(failed + " could not be verified; existing import kept");
        if (superseded > 0) parts.add(superseded + " leftover check" + (superseded == 1 ? "" : "s")
                + " removed (moved to another build earlier; imports kept)");
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
                    || !from.locale().equals(canonicalLocale(receipt.locale()))
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

    /** Dialog body for a move; says when the new build's own import or check is replaced. */
    static String followDetail(BuildFollow follow, boolean replacesExisting) {
        return "The watched client cache now belongs to " + follow.to().version() + ". Applying imports it"
                + " for that build and checks it there on Refresh sources"
                + (replacesExisting ? ", replacing the import or check that build has now" : "")
                + ". Build " + follow.from().version() + " keeps its current import either way.";
    }

    /** True when a finished or failed move changed the new build's binding (reopen it). */
    static boolean followChangedBinding(ClientHotfixStore.FollowResult result, Throwable error) {
        return result != null ? result.bindingChanged() : incomplete(error) != null;
    }

    /** Digest the new build is now bound to after a move that changed it, else null. */
    static String followBoundSha(ClientHotfixStore.FollowResult result, Throwable error) {
        if (result != null) return result.bindingChanged() ? result.summary().sha256() : null;
        var incomplete = incomplete(error);
        return incomplete == null || incomplete.bound == null ? null : incomplete.bound.sha256();
    }

    private static ClientHotfixStore.FollowIncomplete incomplete(Throwable error) {
        for (int i = 0; i < 8 && error != null; i++, error = error.getCause())
            if (error instanceof ClientHotfixStore.FollowIncomplete incomplete) return incomplete;
        return null;
    }

    /**
     * What to tell the user after a move, or null when it simply succeeded. Every text states the
     * actual state of both builds; none claims "unchanged" unless nothing changed.
     */
    static String followOutcome(BuildFollow follow, ClientHotfixStore.FollowResult result, Throwable error) {
        String n = follow.to().version(), m = follow.from().version();
        if (result != null) {
            var notes = new java.util.ArrayList<String>();
            if (result.replacedOtherWatch())
                notes.add("Build " + n + " now checks the followed file instead of another file with the"
                        + " same contents; that other file is no longer checked.");
            if (!result.oldWatchCleared())
                notes.add("Build " + n + " now uses the cache and is checked on Refresh sources. Build " + m
                        + (result.leftoverMarked()
                                ? " still has a leftover check; the next Refresh sources removes it."
                                : " is still checked too: the old check remains. Remove it manually (untick"
                                        + " \"Check on Refresh sources\" for build " + m + ") or apply again."));
            return notes.isEmpty() ? null : String.join(" ", notes);
        }
        if (incomplete(error) != null)
            return "Build " + n + " now uses the cache, but it is not checked on Refresh sources. Import it"
                    + " for build " + n + " again with \"Check on Refresh sources\" ticked to keep it current."
                    + " Build " + m + " keeps its import and its check.";
        return failure(error) + " Nothing changed: build " + n + " and build " + m + " keep their imports and checks.";
    }

    private static String canonicalLocale(String locale) {
        try {
            return com.vibeminer.formats.definitions.WowLocale.parse(locale).code();
        } catch (RuntimeException unknown) {
            return null;
        }
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
                    : "base DB2 source; " + unavailable(info.get().unavailable());
            case HOTFIX -> "OPTIONAL_KEY_DATA".equals(source.reason())
                    ? "local hotfix cache, build " + version + " (carried a key, not needed to read this row)"
                    : "local hotfix cache, build " + version;
            case DELETED -> "removed by local hotfix cache, build " + version;
            case NOT_PUBLIC -> "hidden by local hotfix cache, build " + version;
            // The client hides records its cache marks INVALID; say so rather than "unknown".
            case UNRESOLVED, UNKNOWN -> "INVALID".equals(source.state())
                    ? "hidden by local hotfix cache (INVALID), build " + version
                    : undecoded(source.reason());
        };
        return label + ": " + origin;
    }

    /** Fixed text for a cache record that could not be shown; never includes payload bytes. */
    static String undecoded(String reason) {
        if (HotfixViewInfo.RAW_SCAN_GAVE_UP.equals(reason))
            return "raw-column check inconclusive after 8 tries; local hotfix cache row not shown,"
                    + " no stale base substitution";
        if (reason != null && reason.matches("TRAILING_BYTES:[0-9]{1,6}"))
            return "local hotfix cache row has " + reason.substring("TRAILING_BYTES:".length())
                    + " bytes its layout does not describe; not shown, no stale base substitution";
        return "client-cache source unresolved; no stale base substitution";
    }

    /** Fixed text for why a table reads base rows although a local hotfix cache is loaded. */
    static String unavailable(String reason) {
        return switch (reason) {
            case HotfixViewInfo.TABLE_BUDGET -> "local hotfix cache not applied to this table (too large)";
            case HotfixViewInfo.RETAINED_BUDGET -> "hotfix overlay memory busy, showing base rows";
            case HotfixViewInfo.OPERATION_TABLES ->
                    "hotfix overlay skipped for this request (more than 8 tables), showing base rows";
            case HotfixViewInfo.BASE_UNAVAILABLE ->
                    "hotfix overlay not applied; the base table could not be read";
            default -> "hotfix overlay not applied to this table";
        };
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
