package com.vimi.gui;

import com.vibeminer.concepts.world.WorldGlobalScenes;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WorldPlacements.Vec3;

import java.util.Arrays;
import java.util.List;

/** Authored recorded group facts only: no client files, Source, child process or display. */
public final class GlobalInteriorOutlineTest {
    private GlobalInteriorOutlineTest() {}

    public static void main(String[] args) {
        groupsKeepRecordedNamesFlagsAndOrder();
        groupBoundsAreClippedToTheDrawnSceneAndFrameTheirCentre();
        unusableBoundsStayListedButCannotMoveTheCamera();
        entranceAndFloorsAreReportedUnknownNeverInferred();
        System.out.println(
                "Global interior outline: recorded names/flags, clipped group framing and unknown"
                        + " entrance/floors passed");
    }

    private static WmoFormats.GroupHeader header(long flags, Vec3 lower, Vec3 upper) {
        return new WmoFormats.GroupHeader(
                0, 0, flags, lower, upper, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
    }

    private static WorldGlobalScenes.Group group(
            int ordinal, long id, String name, long flags, Vec3 lower, Vec3 upper) {
        return new WorldGlobalScenes.Group(
                ordinal,
                id,
                flags,
                3,
                1,
                1,
                1,
                false,
                false,
                0,
                "0".repeat(64),
                header(flags, lower, upper),
                name);
    }

    private static final float[] SCENE = {-100, -50, -10, 100, 50, 30};

    private static void groupsKeepRecordedNamesFlagsAndOrder() {
        var outline =
                GlobalInteriorOutline.of(
                        List.of(
                                group(0, 501, "Hall", 0x2005, v(-1, -1, 0), v(1, 1, 2)),
                                group(1, 502, "", 0x0a09, v(-1, -1, 0), v(1, 1, 2)),
                                group(2, 503, null, 0x80002000L, v(-1, -1, 0), v(1, 1, 2))),
                        SCENE);
        var groups = outline.groups();
        require(groups.size() == 3, "every recorded group is listed");
        require(groups.get(0).label().equals("Group 0 · Hall"), groups.get(0).label());
        require(groups.get(1).label().equals("Group 1 · unnamed"), groups.get(1).label());
        require(
                groups.get(2).label().equals("Group 2 · name not recorded"),
                groups.get(2).label());
        require(
                groups.get(0).details().equals("flags 0x2005 (BSP, vertex colors, indoor) · file #501"),
                groups.get(0).details());
        require(
                GlobalInteriorOutline.flagText(0x0a09)
                        .equals("flags 0xa09 (BSP, outdoor, lights, doodads)"),
                GlobalInteriorOutline.flagText(0x0a09));
        require(
                GlobalInteriorOutline.flagText(0x80002000L)
                        .equals("flags 0x80002000 (indoor, other 0x80000000)"),
                "unnamed bits stay raw");
        require(GlobalInteriorOutline.flagText(0).equals("flags 0x0"), "no bits");
    }

    private static void groupBoundsAreClippedToTheDrawnSceneAndFrameTheirCentre() {
        // Recorded WMO-local box x -200..4, y 2..8, z -1..5 -> native x -4..200, y -8..-2.
        var outline =
                GlobalInteriorOutline.of(
                        List.of(group(0, 9, "Lair", 0x2005, v(-200, 2, -1), v(4, 8, 5))), SCENE);
        var g = outline.groups().getFirst();
        require(
                Arrays.equals(g.bounds(), new float[] {-4, -8, -1, 100, -2, 5}),
                Arrays.toString(g.bounds()));
        var camera = g.camera();
        require(
                camera.x() == 48 && camera.y() == -5 && camera.z() == 2,
                "camera at clipped box centre: " + camera);
        require(camera.yaw() == 0 && camera.pitch() == 0, "level inspection camera");
        var copy = g.bounds();
        copy[0] = 99;
        require(g.bounds()[0] == -4, "bounds are detached");
    }

    private static void unusableBoundsStayListedButCannotMoveTheCamera() {
        var outline =
                GlobalInteriorOutline.of(
                        List.of(group(0, 9, "Away", 0x2005, v(500, 500, 500), v(600, 600, 600))),
                        SCENE);
        var g = outline.groups().getFirst();
        require(g.bounds() == null, "disjoint from drawn scene");
        require(g.details().endsWith("bounds outside the camera range"), g.details());
        boolean refused = false;
        try {
            g.camera();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        require(refused, "no camera without usable bounds");
        require(
                GlobalInteriorOutline.clip(
                                new float[] {-5000, 0, 0, -4500, 1, 1},
                                new float[] {-6000, -1, -1, 6000, 2, 2})
                        == null,
                "camera range is bounded");
        require(
                GlobalInteriorOutline.clip(new float[] {0, 0, 0, 1, 1}, SCENE) == null,
                "malformed box");
    }

    private static void entranceAndFloorsAreReportedUnknownNeverInferred() {
        for (var outline :
                List.of(
                        GlobalInteriorOutline.empty(),
                        GlobalInteriorOutline.of(
                                List.of(group(0, 1, "Hall", 0x2005, v(0, 0, 0), v(1, 1, 1))),
                                SCENE))) {
            require(
                    outline.entrance().equals(GlobalInteriorOutline.ENTRANCE_UNKNOWN),
                    "entrance is unknown without a recording source");
            require(outline.entrance().contains("unknown"), outline.entrance());
            require(
                    outline.floors().equals(GlobalInteriorOutline.FLOORS_UNQUALIFIED),
                    "floors are not inferred from height or group order");
        }
        require(GlobalInteriorOutline.empty().groups().isEmpty(), "empty outline");
    }

    private static Vec3 v(float x, float y, float z) {
        return new Vec3(x, y, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
