package com.vimi.gui;

/** Dependency-free checks for the fixture boundary and unknown-size semantics. */
public final class PrototypeTest {
    private PrototypeTest() {}

    public static void main(String[] args) {
        BuildService service = new MockBuildService();
        var builds = service.listBuilds().join();
        require(builds.size() == 8, "Expected eight authored builds");
        var fresh = builds.stream().filter(b -> !b.prepared()).findFirst().orElseThrow();
        require(fresh.downloaded().equals("Not opened"), "Unopened is not zero percent");
        var opened = service.openBuild(fresh).join();
        require(opened.prepared(), "Open marks the returned view prepared");
        require(opened.config().equals(fresh.config()), "Opening preserves exact identity");
        require(!fresh.prepared(), "Opening must not mutate catalog fixtures");
        var unknown = builds.stream().filter(b -> b.prepared() && b.totalGiB() == null).findFirst().orElseThrow();
        require(unknown.downloaded().equals("Size unknown"), "Unknown size must remain explicit");
        var community = builds.stream().filter(b -> b.source() == BuildInfo.BuildSource.COMMUNITY).findFirst().orElseThrow();
        require(!community.current() && !community.prepared(), "Community is neither current nor prepared");
        require(community.regions().isEmpty(), "Community must not invent observed regions");
        require(service.openBuild(community).isCompletedExceptionally(), "History alone cannot authorize opening");
        for (String area : java.util.List.of("Tables", "Creatures")) {
            var entries = MockResearchData.entries(area);
            require(!entries.isEmpty(), area + " needs browsable samples");
            require(entries.stream().map(MockResearchData.Entry::id).distinct().count() == entries.size(),
                    area + " sample identities must be unique");
            for (var entry : entries) {
                if (!entry.sourceTable().isEmpty()) {
                    require(MockResearchData.entries("Tables").stream().anyMatch(table -> table.id().equals(entry.sourceTable())),
                            "Source navigation must target an existing sample table");
                }
            }
        }
        var overview = MockOverviewSnapshot.forBuild(builds.getFirst());
        require(overview.namedFileIds() <= overview.fileIds(), "Named-file coverage needs a matching catalog denominator");
        require(overview.readableTables() <= overview.tableDefinitions(), "Readable tables cannot exceed bundled definitions");
        var unknownOverview = MockOverviewSnapshot.forBuild(unknown);
        require(unknownOverview.fileIds() == null && unknownOverview.readableTables() == null,
                "Unmeasured research scope must stay unknown, not become zero");
        var files = MockFileCatalog.entries();
        require(files.stream().map(MockFileCatalog.FileEntry::id).distinct().count() == files.size(), "File identities must be unique");
        var unnamed = files.get(2);
        require(unnamed.name().equals("Name not known") && unnamed.type().equals("Unknown"), "Unnamed files need no invented name or type");
        require(unnamed.size().equals("Unknown") && unnamed.downloaded().equals("Unknown"), "Unknown must not become zero or not stored");
        var partial = files.get(3);
        require(partial.size().equals("128 KiB–256 KiB"), "Variant size ranges must not be summed");
        require(partial.downloaded().equals("1 of 2 stored"), "Partial retention must count variants");
        require(FilesView.matches(partial, "800004", "Texture · BLP", "Partly stored"), "File filters must combine");
        require(!FilesView.matches(partial, "800004", "Model · M2", "Partly stored"), "Type filter must exclude different types");
        require(files.get(18).size().contains("unknown"), "Known variant size must not hide an unknown variant");
        require(FilesView.matches(files.get(18), "", "All types", "Unknown"), "Mixed unknown status must remain discoverable");
        try (var preparation = new MockPreparationService(false)) {
            var job = preparation.start(fresh, "US");
            require(preparation.start(fresh, "US") == job, "Reattach must share the existing preparation");
            require(job.stage() == 0 && job.active(), "First open begins at configuration verification");
            try {
                preparation.start(builds.getFirst(), "US");
                throw new AssertionError("Different builds cannot silently prepare concurrently");
            } catch (IllegalStateException expected) {
                require(preparation.active() == job, "Busy leaves the original job active");
            }
            preparation.advance(job);
            preparation.fail(job);
            preparation.advance(job);
            require(job.state() == MockPreparationService.State.FAILED, "Failure stays latched until explicit retry");
            var retry = preparation.start(fresh, "US");
            require(retry.generation() > job.generation() && retry.stage() == 0, "Retry has a new generation");
            preparation.advance(job);
            require(retry.stage() == 0, "Stale producer updates cannot advance the retry");
            preparation.cancel(retry);
            require(retry.state() == MockPreparationService.State.STOPPING && retry.active(), "Cancellation acknowledgment is not retirement");
            preparation.advance(retry);
            require(retry.state() == MockPreparationService.State.CANCELED && !retry.active(), "Canceled work must retire");
            var success = preparation.start(fresh, "US");
            for (int index = 0; index < MockPreparationService.STAGES.size(); index++) {
                preparation.advance(success);
            }
            require(success.state() == MockPreparationService.State.READY, "Success follows publication, not transfer alone");
            preparation.cancel(success);
            require(success.state() == MockPreparationService.State.READY, "Late cancellation cannot erase a ready result");
            require(preparation.latest(fresh) == success, "Build row follows the latest preparation generation");
        }
        var items = MockItemCatalog.entries();
        require(items.size() == 10 && items.stream().map(MockItemCatalog.Item::id).distinct().count() == items.size(), "Sample item identities must be unique");
        var sword = items.getFirst();
        require(MockItemCatalog.matches(sword, "WAYFARER", "Weapon", "Sword", "Uncommon", "Main hand"), "Name search and all item filters combine");
        require(!MockItemCatalog.matches(sword, "", "Armor", "Sword", "Uncommon", "Main hand"), "Subclass alone must not override class");
        require(MockItemCatalog.matches(sword, "900001", "All classes", "All subclasses", "All qualities", "All slots"), "Exact item ID search should resolve");
        require(!MockItemCatalog.matches(sword, "90000", "All classes", "All subclasses", "All qualities", "All slots"), "Partial numeric ID must not match");
        require(!MockItemCatalog.matches(sword, "", "All classes", "All subclasses", "Epic", "All slots"), "Quality filter must apply independently");
        require(!MockItemCatalog.matches(sword, "", "All classes", "All subclasses", "All qualities", "Head"), "Slot filter must apply independently");
        for (var item : items) {
            require(PrototypeTest.class.getResource("/items/" + item.icon() + ".jpg") != null, "Every sample icon must be bundled for offline use");
            require(!item.lines().isEmpty(), "Every sample item needs tooltip content");
        }
        System.out.println("Passed mock-boundary, research, catalog, preparation and item-filter checks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
