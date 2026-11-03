package com.vibeminer.concepts.races;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reviewed body geoset/slot rules; separate collection and held-item meshes remain unsupported. */
final class RaceClothingGeometry {
    private RaceClothingGeometry() {}

    static Set<Integer> apply(
            PreviewMesh mesh, Set<Integer> sample, List<RaceAppearancePlan.Item> items) {
        var visible = new HashSet<>(sample);
        boolean robe =
                items.stream()
                        .anyMatch(item -> item.inventoryType() == 20 && item.geosets().size() == 6);
        for (var item :
                items.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (RaceAppearancePlan.Item value) ->
                                                        priority(value.inventoryType()))
                                        .thenComparingLong(RaceAppearancePlan.Item::itemId))
                        .toList()) {
            var g = item.geosets();
            if (g.size() != 6) {
                continue;
            }
            switch (item.inventoryType()) {
                case 4, 5, 20 -> {
                    set(visible, mesh, 8, 1 + g.get(0));
                    set(visible, mesh, 10, 1 + g.get(1));
                    set(visible, mesh, 13, 1 + g.get(2));
                    set(visible, mesh, 22, 1 + g.get(3));
                    set(visible, mesh, 28, 1 + g.get(4));
                }
                case 7 -> {
                    set(visible, mesh, 11, 1 + g.get(0));
                    set(visible, mesh, 9, 1 + g.get(1));
                    if (!robe) {
                        set(visible, mesh, 13, 1 + g.get(2));
                    }
                }
                case 8 -> {
                    set(visible, mesh, 5, robe ? 1 : 1 + g.get(0), robe);
                    set(visible, mesh, 20, g.get(1) == 0 ? 2 : g.get(1));
                }
                case 6 -> set(visible, mesh, 18, 1 + g.get(0));
                case 10 -> {
                    set(visible, mesh, 4, 1 + g.get(0));
                    set(visible, mesh, 23, 1 + g.get(1));
                }
                default -> {
                    /* No reviewed base-body geoset override for this slot. */
                }
            }
        }
        return Set.copyOf(visible);
    }

    private static void set(Set<Integer> visible, PreviewMesh mesh, int group, int variant) {
        set(visible, mesh, group, variant, false);
    }

    private static void set(
            Set<Integer> visible, PreviewMesh mesh, int group, int variant, boolean force) {
        if (!force
                && variant == 1
                && visible.stream().anyMatch(id -> id / 100 == group && id % 100 > 1)) {
            return;
        }
        visible.removeIf(id -> id / 100 == group);
        int target = group * 100 + variant;
        if (mesh.parts().stream().anyMatch(part -> part.id() == target)) {
            visible.add(target);
        }
    }

    static boolean allowsSection(int inventory, long section) {
        return switch (inventory) {
            case 4, 5, 20 -> section >= 0 && section <= 6 && section != 2;
            case 6 -> section == 4 || section == 5;
            case 7 -> section == 5 || section == 6;
            case 8 -> section == 6 || section == 7;
            case 9 -> section == 1;
            case 10 -> section == 1 || section == 2;
            default -> false;
        };
    }

    private static int priority(int inventory) {
        return switch (inventory) {
            case 4, 7 -> 10;
            case 8 -> 11;
            case 5, 20 -> 13;
            case 6 -> 18;
            case 9 -> 19;
            case 10 -> 20;
            default -> 0;
        };
    }
}
