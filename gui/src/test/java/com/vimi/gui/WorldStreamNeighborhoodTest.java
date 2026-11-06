package com.vimi.gui;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import java.util.ArrayList;
import java.util.List;

/** Native heading and sparse inventory behavior for the bounded streaming demand order. */
public final class WorldStreamNeighborhoodTest {
    public static void main(String[] args) {
        var recorded = new ArrayList<WorldFormats.Tile>();
        for (int x = 28; x <= 36; x++) for (int y = 28; y <= 36; y++)
            recorded.add(new WorldFormats.Tile(x, y, 1, 0, 0));
        int[][] wanted = {{31, 32}, {32, 31}, {33, 32}, {32, 33}};
        for (int i = 0; i < wanted.length; i++) {
            var demand = WorldStreamNeighborhood.select(recorded, 32, 32, i * Math.PI / 2,
                    WorldStreamTransport.ViewDistance.FAR);
            require(demand.size() == 25 && demand.size() == WorldStreamTransport.MAX_RESIDENTS,
                    "Only the recorded 5x5 neighborhood is requested");
            require(demand.getFirst().x() == 32 && demand.getFirst().y() == 32,
                    "Camera tile remains nearest for frame admission");
            require(demand.get(1).x() == wanted[i][0] && demand.get(1).y() == wanted[i][1],
                    "First neighboring load follows the native camera heading");
            for (int at = 0; at < demand.size(); at++) {
                int ring = WorldStreamNeighborhood.ring(demand.get(at), 32, 32);
                require(ring == (at == 0 ? 0 : at < 9 ? 1 : 2),
                        "The inner 3x3 loads completely before the outer ring");
            }
            var outer = demand.get(9);
            require(outer.x() == 2 * wanted[i][0] - 32 && outer.y() == 2 * wanted[i][1] - 32,
                    "First outer-ring load follows the native camera heading");
        }
        // NEAR keeps the legacy 3x3: the first nine of the FAR order, nothing beyond ring 1.
        for (int i = 0; i < wanted.length; i++) {
            double yaw = i * Math.PI / 2;
            var near = WorldStreamNeighborhood.select(recorded, 32, 32, yaw,
                    WorldStreamTransport.ViewDistance.NEAR);
            require(near.size() == 9 && near.equals(
                            WorldStreamNeighborhood.select(recorded, 32, 32, yaw,
                                    WorldStreamTransport.ViewDistance.FAR).subList(0, 9))
                            && near.equals(WorldStreamNeighborhood.select(recorded, 32, 32, yaw)),
                    "Near demand is the inner prefix of the far demand and the default");
        }
        require(WorldStreamGpuOwner.viewDistance(null) == WorldStreamTransport.ViewDistance.NEAR
                        && WorldStreamGpuOwner.viewDistance("") == WorldStreamTransport.ViewDistance.NEAR
                        && WorldStreamGpuOwner.viewDistance("near") == WorldStreamTransport.ViewDistance.NEAR
                        && WorldStreamGpuOwner.viewDistance("bogus") == WorldStreamTransport.ViewDistance.NEAR
                        && WorldStreamGpuOwner.viewDistance("Far") == WorldStreamTransport.ViewDistance.FAR
                        && WorldStreamGpuOwner.viewDistance(" far ") == WorldStreamTransport.ViewDistance.FAR,
                "Only an explicit far opts in; the default is near");
        var sparse = List.of(recorded.getFirst(), new WorldFormats.Tile(33, 32, 7, 0, 0),
                new WorldFormats.Tile(34, 30, 7, 0, 0));
        require(WorldStreamNeighborhood.select(sparse, 32, 32, Math.PI, WorldStreamTransport.ViewDistance.FAR)
                        .equals(List.of(sparse.get(1), sparse.get(2))),
                "Missing camera tile is not invented and far inventory is excluded");
        require(WorldStreamNeighborhood.select(recorded, 0, 0, 0).isEmpty(),
                "No recorded neighbors is an empty demand");
        require(recorded.size() == 81, "Inventory is unchanged");
        System.out.println("World demand ordering: native headings, ring order, nearest tile, sparse inventory and bounds passed");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private WorldStreamNeighborhoodTest() {}
}
