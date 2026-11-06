package com.vimi.gui;

import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldExteriorObjects;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.rendering.world.TerrainCamera;
import java.util.List;
import java.util.Map;

/** Inspect card contents: one fixture per kind (terrain, building, doodad, creature, spawn); no FX. */
public final class WorldInspectionTest {
    private WorldInspectionTest() {}

    public static void main(String[] args) {
        terrain();
        buildingAndDoodads();
        creatureModel();
        npcSpawn();
        notes();
        System.out.println("World inspection cards: terrain, building, doodad, set doodad, creature model,"
                + " NPC spawn and notes passed");
    }

    private static void terrain() {
        var hit = new WorldPick.Hit(-640.5, -4120.5, 41.3, 70);
        var known = WorldCurrentArea.resolve(0, 10, WorldCurrentArea.Index.of(List.of(
                new WorldService.Area(1, "Elwynn Forest", 0, 0), new WorldService.Area(10, "Goldshire", 1, 0))));
        var card = WorldInspection.terrain(hit, 0, "Eastern Kingdoms", known, 2);
        require(card.kind() == WorldInspection.Kind.TERRAIN && card.kind().chip.equals("Terrain"), "Terrain chip");
        require(card.title().equals("Elwynn Forest · Goldshire") && card.subtitle().equals("rendered ADT surface"),
                "Title is the area: " + card);
        require(card.lines().equals(List.of("tile 33,39 · x -641 · y -4121", "alt 41 · 70 yd away")),
                "Short lines: " + card.lines());
        require(card.file() == null && card.npcId() == 0
                        && card.detail().equals(WorldPick.describeTerrain(hit, 0, "Eastern Kingdoms", known, 2)),
                "The detail is the full inspection text");
        var unknown = WorldInspection.terrain(hit, 0, null,
                WorldCurrentArea.resolve(0, 42, WorldCurrentArea.Index.of(List.of())), 0);
        require(unknown.title().equals("Terrain") && unknown.subtitle().startsWith("Area unknown ("),
                "An unknown area says so: " + unknown);
    }

    private static void buildingAndDoodads() {
        var eye = new TerrainCamera(0, 0, 0, 0, 0);
        var ray = new WorldPick.Ray(0, 0, 0, 1, .01, .005);
        var names = Map.of(1001L, "world/wmo/authored/keep.wmo", 3001L, "world/doodads/authored/barrel.m2");
        java.util.function.LongFunction<String> lookup = names::get;
        var b = WorldObjectPicks.pick(List.of(WorldObjectPicksTest.wall(32, 100,
                List.of(WorldObjectPicksTest.building(100)))), eye, ray, 2200).orElseThrow();
        var building = WorldInspection.object(b, 0, "Authored continent", lookup);
        require(building.kind() == WorldInspection.Kind.BUILDING && building.title().equals("keep.wmo")
                        && building.subtitle().equals("exterior building · BASIC")
                        && building.file().equals("world/wmo/authored/keep.wmo"),
                "Building card: " + building);
        require(building.lines().get(0).equals("tile 31,32 · x 100 · y 0")
                        && building.lines().get(1).startsWith("alt 0 · ") && building.lines().get(1).endsWith("opaque surface")
                        && building.lines().get(2).equals("WMO FileDataID 1001 · unique ID 789"),
                "Building lines: " + building.lines());
        require(building.detail().equals(WorldObjectPicks.describe(b, 0, "Authored continent", lookup)),
                "Building detail is the full text");

        var doodad = new WorldObjectPicks.Exterior(WorldExteriorObjects.Kind.DOODAD, 7, 55, 3002, 0x40, 0, 0, 1,
                List.of(3102L), List.of(0), 0, WorldObjectPicksTest.key(100));
        var d = WorldInspection.object(WorldObjectPicks.pick(List.of(WorldObjectPicksTest.wall(32, 100,
                List.of(doodad))), eye, ray, 2200).orElseThrow(), 0, null, lookup);
        require(d.kind() == WorldInspection.Kind.DOODAD && d.title().equals("M2 3002") && d.file() == null
                        && d.subtitle().equals("ADT doodad · BASIC"),
                "An unnamed doodad shows its file ID and no file line: " + d);

        var set = new WorldObjectPicks.Exterior(WorldExteriorObjects.Kind.SET_DOODAD, 12, 789, 3001, 0, 1, 0, 1,
                List.of(3101L), List.of(0), 1001, WorldObjectPicksTest.key(100));
        var s = WorldInspection.object(WorldObjectPicks.pick(List.of(WorldObjectPicksTest.wall(32, 100,
                List.of(set))), eye, ray, 2200).orElseThrow(), 0, null, lookup);
        require(s.kind() == WorldInspection.Kind.DOODAD && s.title().equals("barrel.m2")
                        && s.subtitle().equals("WMO set doodad · BASIC · in keep.wmo")
                        && s.file().equals("world/doodads/authored/barrel.m2"),
                "A set doodad names its building: " + s);
        require(WorldInspection.object(b, 0, null, id -> { throw new IllegalStateException("no names"); })
                        .file() == null,
                "A failing name lookup leaves the file unknown");
    }

    private static void creatureModel() {
        var eye = new TerrainCamera(0, 0, 0, 0, 0);
        var ray = new WorldPick.Ray(0, 0, 0, 1, .01, .005);
        var index = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 1, 100, 0, 0.0)));
        var guard = index.near(0, 100, 0, 10, 10).placed().getFirst();
        var choice = WorldTileCreaturesTest.qualified(7, null).choice();
        var picked = WorldObjectPicks.pick(List.of(WorldObjectPicksTest.wall(32, 100, List.of(
                new WorldObjectPicks.Creature(guard, index.npc(1).orElseThrow(), choice, 1,
                        WorldObjectPicksTest.key(100))))), eye, ray, 2200).orElseThrow();
        var card = WorldInspection.object(picked, 0, null, id -> id == 123456 ? "creature/authored/guard.m2" : null);
        require(card.kind() == WorldInspection.Kind.CREATURE && card.title().equals("Authored guard")
                        && card.subtitle().endsWith("NPC ID 1") && card.npcId() == 1
                        && card.file().equals("creature/authored/guard.m2")
                        && card.lines().getLast().equals("display 7 · static rest pose"),
                "Creature card: " + card);
    }

    private static void npcSpawn() {
        var index = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 1, 100, 0, 0.0)));
        var spawn = index.near(0, 100, 0, 10, 10).placed().getFirst();
        var npc = index.npc(1).orElseThrow();
        var card = WorldInspection.spawn(spawn, npc, "Authored", null, true, List.of("Vendors", "Trainers"),
                "Alliance", 3);
        require(card.kind() == WorldInspection.Kind.SPAWN && card.kind().chip.equals("NPC spawn")
                        && card.title().equals("Authored guard") && card.npcId() == 1,
                "Spawn card head: " + card);
        require(card.lines().get(0).startsWith("level ") && card.lines().contains("faction Alliance")
                        && card.lines().contains("roles Vendors, Trainers")
                        && card.lines().contains("3 spawns on this map"),
                "Spawn card lines: " + card.lines());
        require(card.detail().equals(WorldPick.describeSpawn(spawn, npc, "Authored", null, true))
                        && card.detail().contains("model: resolving"),
                "Spawn detail is the full text");
        var bare = WorldInspection.spawn(spawn, null, null, null, false, null, null, -1);
        require(bare.title().equals("NPC 1") && bare.subtitle().startsWith("name unknown")
                        && bare.lines().size() == 1 && bare.lines().getFirst().startsWith("tile "),
                "Without NPC, roles or counts only what is known shows: " + bare);
    }

    private static void notes() {
        var note = WorldInspection.note("Nothing picked · sky");
        require(note.kind() == WorldInspection.Kind.NOTE && note.title().equals("Nothing picked · sky")
                        && note.lines().isEmpty() && note.file() == null && note.detail().equals(note.title()),
                "A note carries only its message");
        require(WorldInspection.base("a/b\\c.m2", "x").equals("c.m2") && WorldInspection.base(null, "x").equals("x"),
                "File base names");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
