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
        predictedTileRanksTilesAheadFirst(recorded);
        for (var distance : WorldStreamTransport.ViewDistance.values()) {
            var beyond = WorldStreamNeighborhood.keep(recorded, 32, 32, distance);
            int side = 2 * distance.radius() + 3;
            require(beyond.size() == side * side - (side - 2) * (side - 2)
                            && beyond.stream().allMatch(tile ->
                                    WorldStreamNeighborhood.ring(tile, 32, 32) == distance.radius() + 1),
                    "Keep is exactly the recorded ring beyond the view distance");
        }
        motionPredictsWithinTheRadiusAndRestartsAfterJumpsAndGaps();
        require(recorded.size() == 81, "Inventory is unchanged");
        System.out.println("World demand ordering: native headings, ring order, nearest tile, sparse inventory and bounds passed");
    }
    /** #366: tiles ahead of a moving camera rank first; membership and the camera tile do not move. */
    private static void predictedTileRanksTilesAheadFirst(List<WorldFormats.Tile> recorded) {
        for (var distance : WorldStreamTransport.ViewDistance.values()) {
            for (int i = 0; i < 4; i++) {
                double yaw = i * Math.PI / 2;
                require(WorldStreamNeighborhood.select(recorded, 32, 32, yaw, distance, 32, 32)
                                .equals(WorldStreamNeighborhood.select(recorded, 32, 32, yaw, distance)),
                        "A still camera keeps exactly the stationary order");
            }
            // Facing +y while flying fast towards +x: the predicted tile is (33, 32).
            var still = WorldStreamNeighborhood.select(recorded, 32, 32, Math.PI / 2, distance);
            var moving = WorldStreamNeighborhood.select(recorded, 32, 32, Math.PI / 2, distance, 33, 32);
            require(new java.util.HashSet<>(moving).equals(new java.util.HashSet<>(still)),
                    "Prediction reorders the same tiles, never changes membership");
            require(moving.getFirst().x() == 32 && moving.getFirst().y() == 32,
                    "The camera tile stays first: presentation waits on it");
            require(moving.get(1).x() == 33 && moving.get(1).y() == 32,
                    "The predicted tile loads right after the camera tile");
            int firstBehind = -1, lastAhead = -1;
            for (int at = 1; at < moving.size(); at++) {
                if (moving.get(at).x() > 32) lastAhead = at;
                if (moving.get(at).x() < 32 && firstBehind < 0) firstBehind = at;
            }
            require(firstBehind > 0 && lastAhead > 0, "Tiles exist ahead and behind");
            int aheadInnerRing = 0;
            for (int at = 1; at < 1 + 3; at++) if (moving.get(at).x() > 32) aheadInnerRing++;
            require(aheadInnerRing == 3, "Tiles ahead load right after the camera tile");
            int behindAt = -1;
            for (int at = 0; at < moving.size(); at++)
                if (moving.get(at).x() == 31 && moving.get(at).y() == 32) behindAt = at;
            int stillBehindAt = still.indexOf(moving.get(behindAt));
            require(behindAt > stillBehindAt, "The tile right behind drops in rank while moving");
        }
    }

    private static void motionPredictsWithinTheRadiusAndRestartsAfterJumpsAndGaps() {
        var motion = new WorldStreamNeighborhood.Motion();
        long t = 1_000_000_000L, tick = 100_000_000L; // 10 Hz, as the explorer's nearby refresh
        int[] ahead = motion.ahead(32.5, 32.5, t, 1);
        require(ahead[0] == 32 && ahead[1] == 32, "A first sample predicts the camera tile");
        double x = 32.5;
        for (int i = 1; i <= 30; i++) {
            x += 0.08; // 0.8 tiles a second towards +x
            ahead = motion.ahead(x, 32.5, t + i * tick, 1);
        }
        // About 0.8 tiles/s x 1.2 s ahead of x = 34.9: the tile at 35.86, clamped to the radius.
        require(ahead[0] == 35 && ahead[1] == 32,
                "Steady motion predicts the tile about a lead ahead: " + ahead[0] + "," + ahead[1]);
        // A wheel zoom 10 ms after a sample moves the camera 0.05 tiles: position only. Measured,
        // it would read as 5 tiles/s and push the prediction past a whole extra tile.
        var zoomed = new WorldStreamNeighborhood.Motion();
        double z = 32.5;
        for (int i = 0; i <= 30; i++) {
            ahead = zoomed.ahead(z, 32.5, t + i * tick, 2);
            if (i < 30) z += 0.08; // 0.8 tiles a second
        }
        ahead = zoomed.ahead(z + 0.05, 32.5, t + 30 * tick + 10_000_000L, 2);
        require(ahead[0] == (int) Math.floor(z + 0.05 + 0.96) && ahead[1] == 32,
                "A sample within 50 ms never spikes the velocity: " + ahead[0]);
        // The next full sample still measures from the last full one, at the steady speed.
        ahead = zoomed.ahead(z + 0.08, 32.5, t + 31 * tick, 2);
        require(ahead[0] == (int) Math.floor(z + 0.08 + 0.96), "Speed stays steady: " + ahead[0]);
        // No sample for 0.6 s: the camera has stopped, the prediction is the camera tile.
        ahead = zoomed.ahead(z + 0.08, 32.5, t + 31 * tick + 600_000_000L, 2);
        require(ahead[0] == (int) Math.floor(z + 0.08), "A stale velocity decays to rest");
        var fast = new WorldStreamNeighborhood.Motion();
        double fx = 10.5;
        for (int i = 0; i <= 30; i++) {
            ahead = fast.ahead(fx, 10.5, t + i * tick, 2);
            fx += 0.5; // 5 tiles a second
        }
        require(ahead[0] == (int) Math.floor(fx - 0.5 + 2) && ahead[1] == 10,
                "The prediction never reaches past the view radius");
        ahead = fast.ahead(fx + 40, 10.5, t + 31 * tick, 2);
        require(ahead[0] == (int) Math.floor(fx + 40) && ahead[1] == 10, "A jump restarts at rest");
        fast.ahead(fx + 40.5, 10.5, t + 32 * tick, 2);
        ahead = fast.ahead(fx + 40.9, 10.5, t + 32 * tick + 2_000_000_000L, 2);
        require(ahead[0] == (int) Math.floor(fx + 40.9), "A long gap restarts at rest");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private WorldStreamNeighborhoodTest() {}
}
