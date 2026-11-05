package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.TalentTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.HotfixViewInfo;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Origin labels are metadata only, distinct from spell values or unknown provider messages. */
public final class ClientHotfixDetailsTest {
    private ClientHotfixDetailsTest() {}

    public static void main(String[] args) {
        BuildTables tables = (BuildTables) Proxy.newProxyInstance(BuildTables.class.getClassLoader(),
                new Class<?>[] {BuildTables.class}, (proxy, method, values) -> {
                    if (method.getName().equals("hotfixInfo")) {
                        var ref = (TableTypes.Ref) values[0];
                        var id = (Long) values[1];
                        if (id != null && id == 998) return CompletableFuture.completedFuture(Optional.empty());
                        if (id != null && (id == 997 || id == 996)) {
                            var unresolved = new HotfixViewInfo.RowSource(HotfixViewInfo.Origin.UNRESOLVED,
                                    1, 70, 5, 1, id == 997 ? "INVALID" : "VALID", "UNKNOWN");
                            return CompletableFuture.completedFuture(Optional.of(new HotfixViewInfo(
                                    "private-input", "private-identity", "policy-v1", 10, 9, 0, 0, 0, 0, 1, 0,
                                    unresolved)));
                        }
                        if (id != null && (id == 993 || id == 992)) {
                            var undecoded = new HotfixViewInfo.RowSource(HotfixViewInfo.Origin.UNRESOLVED,
                                    1, 70, 5, 1, "VALID", id == 993 ? "OPTIONAL_KEY_DATA" : "TRAILING_BYTES:28");
                            return CompletableFuture.completedFuture(Optional.of(new HotfixViewInfo(
                                    "private-input", "private-identity", "policy-v1", 10, 9, 0, 0, 0, 0, 1, 0,
                                    undecoded)));
                        }
                        if (id != null && (id == 995 || id == 994)) {
                            String reason = id == 995 ? HotfixViewInfo.TABLE_BUDGET : HotfixViewInfo.RETAINED_BUDGET;
                            var base = new HotfixViewInfo.RowSource(HotfixViewInfo.Origin.BASE,
                                    null, null, null, null, null, reason);
                            return CompletableFuture.completedFuture(Optional.of(new HotfixViewInfo(
                                    "private-input", null, "policy-v1", 10, 10, 0, 0, 0, 0, 0, 0,
                                    base, reason)));
                        }
                        var origin = ref.name().equals("SpellName") || ref.name().equals("SpellMisc")
                                ? HotfixViewInfo.Origin.HOTFIX : HotfixViewInfo.Origin.BASE;
                        if (id != null && id == 999) origin = HotfixViewInfo.Origin.NOT_PUBLIC;
                        return CompletableFuture.completedFuture(Optional.of(new HotfixViewInfo(
                                "private-input", "private-identity", "policy-v1", 10, 11, 1, 1, 0, 0, 0, 0,
                                id == null ? null : new HotfixViewInfo.RowSource(origin, 1, 70, -1, 1, "VALID"))));
                    }
                    if (method.getName().equals("page")) {
                        var request = (TableTypes.PageRequest) values[0];
                        check(request.filters().size() == 3, "Exact icon relationship filters");
                        return CompletableFuture.completedFuture(new TableTypes.Page(null, TableTypes.PageState.OK,
                                List.of(new TableTypes.Row(0, new Db2Types.Row(55, false, null,
                                        List.of("never display cell data"), List.of()))), null, "identity", 1, null, null));
                    }
                    throw new AssertionError("Unexpected table operation");
                });
        var talent = new TalentTypes.Talent(3, 0, 0, List.of(1328613L), List.of(), "Name", "Text", 123L);
        var lines = ClientHotfixDetails.talent(tables, talent, "1.60.1.70707", Cancellation.NONE).join();
        check(lines.get(0).equals("Tree record: base DB2 source"), "Base tree origin");
        check(lines.get(1).contains("local hotfix cache, build 1.60.1.70707"), "Hotfix name origin");
        check(lines.get(2).equals("Description: base DB2 source"), "Base description origin");
        check(lines.get(3).contains("local hotfix cache"), "Actual icon row origin");
        check(!String.join("\n", lines).contains("private-")
                && !String.join("\n", lines).contains("cell data"), "No payload or private identity display");
        var trait = new TalentTypes.Talent(104923, 0, 1, List.of(1328613L), 5, List.of(), "Name", "Text", 123L,
                new TalentTypes.Origin(TalentTypes.Model.TRAIT_TREE, 1089L, 129583L, 134384L, 1620L, 2130L,
                        null, false, false));
        var traitLines = ClientHotfixDetails.talent(tables, trait, "1.60.1.70707", Cancellation.NONE).join();
        check(traitLines.get(0).equals("Trait node record: base DB2 source")
                && traitLines.get(1).equals("Trait entry record: base DB2 source")
                && traitLines.get(2).equals("Trait definition record: base DB2 source")
                && traitLines.get(3).contains("local hotfix cache"), "Trait talents report their trait rows");
        check(ClientHotfixDetails.row(tables, "Spell", 999, "Description", "70707", Cancellation.NONE)
                .join().contains("hidden"), "Not-public origin stays explicit");
        check(ClientHotfixDetails.row(tables, "Item", 997, "Item", "70707", Cancellation.NONE)
                .join().equals("Item: hidden by local hotfix cache (INVALID), build 70707"),
                "INVALID cache records are explained as hidden by the cache");
        check(ClientHotfixDetails.row(tables, "Item", 996, "Item", "70707", Cancellation.NONE)
                .join().contains("unresolved"), "Other unresolved records stay unresolved");
        check(ClientHotfixDetails.row(tables, "BroadcastText", 993, "Text", "70707", Cancellation.NONE).join()
                        .equals("Text: local hotfix cache row carries a key that is not applied; not shown, no stale"
                                + " base substitution"),
                "Optional key data has its own reason");
        check(ClientHotfixDetails.row(tables, "BroadcastText", 992, "Text", "70707", Cancellation.NONE).join()
                        .contains("has 28 bytes its layout does not describe"),
                "Trailing bytes state their count");
        check(ClientHotfixDetails.undecoded("TRAILING_BYTES:<script>").contains("unresolved"),
                "Only a fixed-format count is ever shown");
        check(ClientHotfixDetails.row(tables, "ItemSparse", 995, "Item", "70707", Cancellation.NONE)
                .join().contains("not applied to this table (too large)"), "Oversized overlay is visible");
        check(ClientHotfixDetails.row(tables, "ItemSparse", 994, "Item", "70707", Cancellation.NONE)
                .join().contains("overlay memory busy, showing base rows"), "Busy overlay memory is not called too large");
        check(ClientHotfixDetails.unavailable(HotfixViewInfo.OPERATION_TABLES).contains("more than 8 tables")
                && !ClientHotfixDetails.unavailable(HotfixViewInfo.OPERATION_TABLES).contains("memory busy"),
                "Too many tables in one request has its own reason");
        check(ClientHotfixDetails.unavailable(HotfixViewInfo.BASE_UNAVAILABLE).contains("base table could not be read"),
                "Unreadable base has its own reason");
        check(ClientHotfixDetails.row(tables, "Spell", 998, "Description", "70707", Cancellation.NONE)
                .join().contains("origin not retained"), "Evicted metadata does not imply base origin");
        var header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x48544658).putInt(9).putInt(70450).array();
        try {
            HotfixCacheReader.inspect(com.vibeminer.formats.db2.Db2ByteSource.memory(header), 70707,
                    new HotfixCacheReader.Limits(), Cancellation.NONE).join();
            throw new AssertionError("Wrong build accepted");
        } catch (java.util.concurrent.CompletionException mismatch) {
            check(ClientHotfixDetails.failure(mismatch).contains("different client build"),
                    "Build mismatch remains useful");
        }
        var pathFailure = new java.io.UncheckedIOException("storage failed", new java.io.IOException("/private/client/path"));
        check(!ClientHotfixDetails.failure(pathFailure).contains("/private"), "No original path in failure label");
        var trap = new Error() {
            @Override public String getMessage() { throw new AssertionError("Foreign message must not be read"); }
        };
        check(!ClientHotfixDetails.failure(new java.util.concurrent.CompletionException("fixed", trap)).isBlank(),
                "Foreign exception accessors are not invoked");
        refreshSummariesUseFixedVocabulary();
        System.out.println("Client hotfix source labels, refresh summaries and redacted failures passed");
    }

    private static void refreshSummariesUseFixedVocabulary() {
        var scope = new ClientHotfixStore.Scope(
                new com.vibeminer.builds.BuildTypes.BuildRef("wow", "01".repeat(16), "02".repeat(16), "us"),
                "enUS", "1.60.1.70707");
        var summary = new ClientHotfixStore.Summary(70707, "enUS", "1.60.1.70707", "a".repeat(64), 79, 1,
                java.time.Instant.EPOCH);
        var privatePath = new java.io.IOException("/home/someone/World/Cache/DBCache.bin");
        var results = List.of(
                new ClientHotfixStore.RefreshResult(scope, ClientHotfixStore.RefreshOutcome.UPDATED, summary, 70707, null),
                new ClientHotfixStore.RefreshResult(scope, ClientHotfixStore.RefreshOutcome.UNCHANGED, summary, 70707, null),
                new ClientHotfixStore.RefreshResult(scope, ClientHotfixStore.RefreshOutcome.UNCHANGED, summary, 70707, null),
                new ClientHotfixStore.RefreshResult(scope, ClientHotfixStore.RefreshOutcome.CLIENT_BUILD_CHANGED,
                        summary, 70800, null),
                new ClientHotfixStore.RefreshResult(scope, ClientHotfixStore.RefreshOutcome.MISSING, summary, 0, null),
                new ClientHotfixStore.RefreshResult(null, ClientHotfixStore.RefreshOutcome.FAILED, null, 0, privatePath));
        String text = ClientHotfixDetails.refreshSummary(results);
        check(text.equals("1 newer cache imported; reopen the build to apply · 2 unchanged"
                + " · 1 now for another client build; existing import kept"
                + " · 1 not found; existing import kept · 1 could not be verified; existing import kept"),
                "Refresh summary counts each outcome: " + text);
        check(!text.contains("/home") && !text.contains("DBCache"), "Refresh summary never includes paths");
        check(ClientHotfixDetails.refreshFailed(results), "Verification failure fails the refresh row");
        check(!ClientHotfixDetails.refreshFailed(results.subList(0, 5)),
                "Missing or other-build originals are not refresh failures");
        check(ClientHotfixDetails.refreshSummary(List.of()).equals("No client caches are marked for checking"),
                "Empty refresh summary is explicit");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
