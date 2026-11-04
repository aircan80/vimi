package com.vimi.gui;

import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.List;

/** Native heading and sparse inventory behavior for the bounded streaming demand order. */
public final class WorldStreamNeighborhoodTest {
    public static void main(String[] args) {
        var recorded = new ArrayList<WorldFormats.Tile>();
        for (int x = 30; x <= 34; x++) for (int y = 30; y <= 34; y++)
            recorded.add(new WorldFormats.Tile(x, y, 1, 0, 0));
        int[][] wanted = {{31, 32}, {32, 31}, {33, 32}, {32, 33}};
        for (int i = 0; i < wanted.length; i++) {
            var demand = WorldStreamNeighborhood.select(recorded, 32, 32, i * Math.PI / 2);
            require(demand.size() == 9, "Only the recorded 3x3 neighborhood is requested");
            require(demand.getFirst().x() == 32 && demand.getFirst().y() == 32,
                    "Camera tile remains nearest for frame admission");
            require(demand.get(1).x() == wanted[i][0] && demand.get(1).y() == wanted[i][1],
                    "First neighboring load follows the native camera heading");
        }
        var sparse = List.of(recorded.getFirst(), new WorldFormats.Tile(33, 32, 7, 0, 0));
        require(WorldStreamNeighborhood.select(sparse, 32, 32, Math.PI).equals(List.of(sparse.get(1))),
                "Missing camera tile is not invented and far inventory is excluded");
        require(WorldStreamNeighborhood.select(recorded, 0, 0, 0).isEmpty(),
                "No recorded neighbors is an empty demand");
        require(recorded.size() == 25, "Inventory is unchanged");
        System.out.println("World demand ordering: native headings, nearest tile, sparse inventory and bounds passed");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private WorldStreamNeighborhoodTest() {}
}
