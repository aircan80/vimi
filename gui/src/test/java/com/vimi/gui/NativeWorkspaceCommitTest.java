package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.Builds;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.effects.BuildItemEffects;
import com.vibeminer.concepts.items.BuildItems;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TablesService;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resource publication contract; reflection supplies staged fixtures without native I/O or assets.
 */
public final class NativeWorkspaceCommitTest {
    private NativeWorkspaceCommitTest() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T resource(Class<T> type, AtomicInteger closed) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                closed.incrementAndGet();
                                return null;
                            }
                            if (method.getName().equals("closeAsync")) {
                                closed.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            }
                            if (method.getName().equals("equals")) return proxy == args[0];
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(proxy);
                            throw new AssertionError("Unexpected resource operation " + method);
                        }));
    }

    private static <T> T drainingResource(Class<T> type, AtomicInteger closed, AtomicInteger tablesClosed) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                require(tablesClosed.get() == 0, type.getSimpleName() + " drains before borrowed tables close");
                closed.incrementAndGet();
                return null;
            }
            throw new AssertionError("Projection remains cold during publication: " + method);
        }));
    }

    private static Object workspace(
            BuildTables tables,
            BuildItems items,
            BuildCreatures creatures,
            BuildItemEffects effects,
            long revision)
            throws Exception {
        return workspace(tables, items, creatures, effects, null, revision);
    }

    private static Object workspace(
            BuildTables tables,
            BuildItems items,
            BuildCreatures creatures,
            BuildItemEffects effects,
            com.vibeminer.concepts.talents.BuildTalents talents,
            long revision)
            throws Exception {
        return workspace(tables, items, creatures, effects, talents, null, revision);
    }

    private static Object workspace(BuildTables tables, BuildItems items, BuildCreatures creatures,
            BuildItemEffects effects, com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races, long revision) throws Exception {
        return workspace(tables,items,creatures,effects,talents,races,null,revision);
    }

    private static Object workspace(BuildTables tables,BuildItems items,BuildCreatures creatures,
            BuildItemEffects effects,com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races,com.vibeminer.concepts.world.BuildWorld world,long revision) throws Exception {
        return workspace(tables, items, creatures, effects, talents, races, world, null, null, revision);
    }

    private static Object workspace(BuildTables tables, BuildItems items, BuildCreatures creatures,
            BuildItemEffects effects, com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races, com.vibeminer.concepts.world.BuildWorld world,
            com.vibeminer.concepts.mounts.BuildMounts mounts,
            com.vibeminer.concepts.professions.BuildProfessions professions, long revision) throws Exception {
        return workspace(tables, items, creatures, effects, talents, races, world, mounts, professions, null, revision);
    }

    private static Object workspace(BuildTables tables, BuildItems items, BuildCreatures creatures,
            BuildItemEffects effects, com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races, com.vibeminer.concepts.world.BuildWorld world,
            com.vibeminer.concepts.mounts.BuildMounts mounts,
            com.vibeminer.concepts.professions.BuildProfessions professions,
            com.vibeminer.concepts.dungeons.BuildDungeons dungeons, long revision) throws Exception {
        return workspace(tables, items, creatures, effects, talents, races, world, mounts, professions, dungeons, null, revision);
    }

    private static Object workspace(BuildTables tables, BuildItems items, BuildCreatures creatures,
            BuildItemEffects effects, com.vibeminer.concepts.talents.BuildTalents talents,
            com.vibeminer.concepts.races.BuildRaces races, com.vibeminer.concepts.world.BuildWorld world,
            com.vibeminer.concepts.mounts.BuildMounts mounts,
            com.vibeminer.concepts.professions.BuildProfessions professions,
            com.vibeminer.concepts.dungeons.BuildDungeons dungeons,
            com.vibeminer.concepts.locations.BuildLocationMaps locationMaps, long revision) throws Exception {
        var type = Class.forName("com.vimi.gui.NativePreparationWork$Workspace");
        var constructor =
                type.getDeclaredConstructor(
                        BuildTables.class,
                        TablesService.class,
                        BuildItems.class,
                        BuildCreatures.class,
                        BuildItemEffects.class,
                        com.vibeminer.concepts.talents.BuildTalents.class,
                        com.vibeminer.concepts.races.BuildRaces.class,
                        com.vibeminer.concepts.world.BuildWorld.class,
                        com.vibeminer.concepts.mounts.BuildMounts.class,
                        com.vibeminer.concepts.professions.BuildProfessions.class,
                        com.vibeminer.concepts.dungeons.BuildDungeons.class,
                        com.vibeminer.concepts.locations.BuildLocationMaps.class,
                        long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tables, null, items, creatures, effects, talents, races, world, mounts, professions, dungeons, locationMaps, revision);
    }

    private static void set(NativePreparationWork work, String field, Object value)
            throws Exception {
        var member = NativePreparationWork.class.getDeclaredField(field);
        member.setAccessible(true);
        member.set(work, value);
    }

    public static void main(String[] args) throws Exception {
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3.45678",
                        "us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        var evidence = new BuildInfo.RegionEvidence("us", "b".repeat(32), true, null, null);
        ObjectSource online =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, values) -> {
                                    throw new AssertionError(
                                            "No network/storage work in publication");
                                });
        ObjectSource local =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, values) -> {
                                    throw new AssertionError("No read work in publication");
                                });
        Builds builds =
                (build, token) ->
                        CompletableFuture.failedFuture(
                                new AssertionError("No opening during publication"));
        var work =
                new NativePreparationWork(
                        info, evidence, "enUS", builds, online, null, null, local);
        var oldClosed = new AtomicInteger();
        var abandonedClosed = new AtomicInteger();
        var nextClosed = new AtomicInteger();
        var oldTable = resource(BuildTables.class, oldClosed);
        var oldItem = resource(BuildItems.class, oldClosed);
        var talentsClosed = new AtomicInteger();
        var oldTalents =
                (com.vibeminer.concepts.talents.BuildTalents)
                        Proxy.newProxyInstance(
                                com.vibeminer.concepts.talents.BuildTalents.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.concepts.talents.BuildTalents.class},
                                (proxy, method, args1) -> {
                                    if (method.getName().equals("close")) {
                                        require(
                                                oldClosed.get() == 0,
                                                "Talent reads must drain before their borrowed"
                                                    + " tables close");
                                        talentsClosed.incrementAndGet();
                                        return null;
                                    }
                                    throw new AssertionError(
                                            "Talent projection must remain cold during workspace"
                                                + " publication");
                                });
        var racesClosed = new AtomicInteger();
        var oldRaces = (com.vibeminer.concepts.races.BuildRaces) Proxy.newProxyInstance(
                com.vibeminer.concepts.races.BuildRaces.class.getClassLoader(),
                new Class<?>[]{com.vibeminer.concepts.races.BuildRaces.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("close")) {
                        require(oldClosed.get() == 0, "Race reads drain before borrowed tables close");
                        racesClosed.incrementAndGet(); return null;
                    }
                    throw new AssertionError("Races projection remains cold during publication");
                });
        var worldClosed=new AtomicInteger();
        var oldWorld=(com.vibeminer.concepts.world.BuildWorld)Proxy.newProxyInstance(
            com.vibeminer.concepts.world.BuildWorld.class.getClassLoader(),new Class<?>[]{com.vibeminer.concepts.world.BuildWorld.class},(proxy,method,values)->{
                if(method.getName().equals("close")){require(oldClosed.get()==0,"World reads drain before borrowed tables close");worldClosed.incrementAndGet();return null;}
                throw new AssertionError("World projection remains cold during publication");
            });
        var mountsClosed = new AtomicInteger();
        var professionsClosed = new AtomicInteger();
        var dungeonsClosed = new AtomicInteger();
        var mapsClosed = new AtomicInteger();
        var oldMaps = drainingResource(com.vibeminer.concepts.locations.BuildLocationMaps.class, mapsClosed, oldClosed);
        var oldMounts = drainingResource(com.vibeminer.concepts.mounts.BuildMounts.class, mountsClosed, oldClosed);
        var oldProfessions = drainingResource(com.vibeminer.concepts.professions.BuildProfessions.class, professionsClosed, oldClosed);
        var oldDungeons = drainingResource(com.vibeminer.concepts.dungeons.BuildDungeons.class, dungeonsClosed, oldClosed);
        set(work, "published", workspace(oldTable, oldItem, null, null, oldTalents, oldRaces, oldWorld, oldMounts, oldProfessions, oldDungeons, oldMaps, 5));
        var abandoned = resource(BuildTables.class, abandonedClosed);
        set(work, "pending", workspace(abandoned, null, null, null, 0));
        set(work, "pendingWarmed", true);
        var canceled = new Cancellation();
        canceled.cancel();
        try {
            work.commit(canceled);
            throw new AssertionError("Canceled generation committed");
        } catch (java.util.concurrent.CancellationException expected) {
        }
        work.abortPending();
        require(
                work.tablesHandle() == oldTable
                        && work.itemsHandle() == oldItem
                        && work.racesHandle() == oldRaces
                        && work.worldHandle() == oldWorld
                        && work.mountsHandle() == oldMounts
                        && work.professionsHandle() == oldProfessions
                        && work.dungeonsHandle() == oldDungeons
                        && work.locationMapsHandle() == oldMaps
                        && work.revision() == 5,
                "Canceled preparation preserves all published handles and revision");
        require(
                oldClosed.get() == 0 && abandonedClosed.get() == 1,
                "Abort closes staged resources only");
        set(
                work,
                "pending",
                workspace(resource(BuildTables.class, abandonedClosed), null, null, null, 0));
        set(work, "pendingWarmed", false);
        try {
            work.commit(Cancellation.NONE);
            throw new AssertionError("Unwarmed generation committed");
        } catch (IllegalStateException expected) {
        }
        work.abortPending();
        require(
                work.tablesHandle() == oldTable
                        && work.itemsHandle() == oldItem
                        && oldClosed.get() == 0,
                "Failed lookup preparation cannot replace or close the published generation");
        require(abandonedClosed.get() == 2, "Unwarmed pending table is cleaned up");
        var nextTable = resource(BuildTables.class, nextClosed);
        var nextItem = resource(BuildItems.class, nextClosed);
        var nextDungeonsClosed = new AtomicInteger();
        var nextDungeons = drainingResource(com.vibeminer.concepts.dungeons.BuildDungeons.class, nextDungeonsClosed, nextClosed);
        set(work, "pending", workspace(nextTable, nextItem, null, null, null, null, null, null, null, nextDungeons, 0));
        set(work, "pendingWarmed", true);
        work.commit(Cancellation.NONE);
        require(
                work.tablesHandle() == nextTable
                        && work.itemsHandle() == nextItem
                        && work.dungeonsHandle() == nextDungeons
                        && work.revision() == 6,
                "One commit publishes table and concept generation together");
        require(
                oldClosed.get() == 0 && nextClosed.get() == 0 && talentsClosed.get() == 0,
                "Commit does no resource I/O");
        work.retireCommitted();
        work.close();
        require(
                oldClosed.get() == 2 && nextClosed.get() == 2,
                "Close waits for retirement and closes the live generation");
        require(talentsClosed.get() == 1, "The retired talent component closes exactly once");
        require(racesClosed.get() == 1, "The retired race component closes exactly once");
        require(worldClosed.get()==1,"The retired world component closes exactly once");
        require(mountsClosed.get() == 1, "Retired mounts drain exactly once before tables");
        require(professionsClosed.get() == 1, "Retired professions drain exactly once before tables");
        require(dungeonsClosed.get() == 1, "Retired dungeons drain exactly once before tables");
        require(nextDungeonsClosed.get() == 1, "Published dungeons drain exactly once before tables");
        require(mapsClosed.get() == 1, "Retired map reads drain exactly once before tables");
        metadataListingDoesNotReadPayloads(info, evidence, online, local);
        System.out.println(
                "Passed atomic workspace commit, cancellation, abort, retirement and metadata-only"
                        + " Files contracts");
    }

    private static void metadataListingDoesNotReadPayloads(
            BuildInfo info,
            BuildInfo.RegionEvidence evidence,
            ObjectSource online,
            ObjectSource local)
            throws Exception {
        var ref =
                new com.vibeminer.builds.BuildTypes.BuildRef(
                        info.product(), info.config(), evidence.cdnConfig(), evidence.region());
        var key = com.vibeminer.network.CdnKey.parse("c".repeat(32));
        var metadata =
                new com.vibeminer.builds.BuildTypes.BuildMetadata(
                        "fixture", key, key, key, 0, 1, java.util.List.of("enUS"));
        var enumerations = new AtomicInteger();
        var build =
                (com.vibeminer.builds.Build)
                        Proxy.newProxyInstance(
                                com.vibeminer.builds.Build.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.builds.Build.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" -> ref;
                                            case "metadata" -> metadata;
                                            case "fileDataIds" -> {
                                                enumerations.incrementAndGet();
                                                yield java.util.List.of(1L);
                                            }
                                            case "resolve" ->
                                                    new com.vibeminer.builds.BuildTypes
                                                            .FileResolution(
                                                            com.vibeminer.builds.BuildTypes
                                                                    .FileResolutionState.FOUND,
                                                            1L,
                                                            java.util.List.of(
                                                                    new com.vibeminer.builds
                                                                            .BuildTypes.FileVariant(
                                                                            com.vibeminer.files
                                                                                    .FileLocales
                                                                                    .bitOf("enUS"),
                                                                            0,
                                                                            key,
                                                                            128L,
                                                                            key,
                                                                            64L,
                                                                            null)));
                                            default ->
                                                    throw new AssertionError(
                                                            "Catalog preparation touched"
                                                                    + " payload/CDN path: "
                                                                    + method);
                                        });
        Builds loader =
                (wanted, token) ->
                        CompletableFuture.completedFuture(
                                new com.vibeminer.builds.BuildTypes.BuildOpenResult(
                                        ref,
                                        com.vibeminer.builds.BuildTypes.BuildOpenState.OPEN,
                                        build,
                                        null,
                                        null,
                                        null,
                                        null));
        var generated = new com.vibeminer.generated.MemoryGeneratedStore();
        var work =
                new NativePreparationWork(
                        info, evidence, "enUS", loader, online, generated, null, local);
        var files =
                (com.vibeminer.files.BuildFiles)
                        Proxy.newProxyInstance(
                                com.vibeminer.files.BuildFiles.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("close")) return null;
                                    throw new AssertionError(
                                            "Baseline must not read/list payloads, names or"
                                                    + " presence: "
                                                    + method);
                                });
        set(work, "fileHandle", files);
        try {
            var summary = work.files(counter -> {}, Cancellation.NONE);
            require(
                    summary.files() == 1,
                    "Metadata-only Files summary must include the cached media entry");
            int scans = enumerations.get();
            var restored = work.files(counter -> {}, Cancellation.NONE);
            require(
                    restored.files() == 1 && !restored.presenceAssessed(),
                    "Restore immutable facts without inventing current presence");
            require(enumerations.get() == scans, "Reopen must skip the file enumeration");
        } finally {
            work.close();
        }
    }
}
