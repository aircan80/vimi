package com.vibeminer.concepts.races;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reviewed body geoset/slot rules; separate collection and held-item meshes remain unsupported.
 *
 * <p>Tabards and capes follow the client's geoset code as wowdev decompiled it
 * (DB/ItemDisplayInfo/GeosRenderPrep, the WoD version of {@code
 * CCharacterComponent::GeosRenderPrep}):
 *
 * <ul>
 *   <li>A tabard whose GeosetGroup[0] is above 0 shows group 12 unless a dress is worn (a chest
 *       that sets the robe group, or without a chest, trousers that do): variant 3 (1203) when the
 *       belt's display has Flags 0x200, which wowdev's flag table names "clips lower tabard
 *       geoset", else 1 + GeosetGroup[0] (1202, "tabard"). A shown tabard keeps the chest's or
 *       shirt's GeosetGroup[1] (group 10) and trousers' GeosetGroup[0] up to 2 (group 11) from
 *       applying.
 *   <li>Any tabard without Flags 0x100000 sets the torso (group 22) to 2202, "armored torso";
 *       without a tabard the chest's GeosetGroup[3] does.
 *   <li>A cape whose GeosetGroup[0] is above 0 shows group 15 variant 1 + GeosetGroup[0], the cloak
 *       style (wowdev Character_Customization, 15: "various cloak styles").
 * </ul>
 *
 * A tabard or cape whose GeosetGroup[0] is 0 changes no geoset, as in the client.
 */
final class RaceClothingGeometry {
    private RaceClothingGeometry() {}

    /**
     * A belt display's Flags bit 0x200: "clips lower tabard geoset" (wowdev DB/ItemDisplayInfo).
     */
    static final long CLIPS_LOWER_TABARD = 0x200;

    /**
     * A tabard display's Flags bit 0x100000: the client then leaves the torso to the chest. wowdev
     * lists it as possibly "don't render this item", used by items "No Tabard" and "No Boots".
     */
    static final long TABARD_KEEPS_TORSO = 0x100000;

    static Set<Integer> apply(
            PreviewMesh mesh, Set<Integer> sample, List<RaceAppearancePlan.Item> items) {
        var visible = new HashSet<>(sample);
        boolean robe =
                items.stream()
                        .anyMatch(item -> item.inventoryType() == 20 && item.geosets().size() == 6);
        boolean chest =
                items.stream()
                        .anyMatch(item -> item.inventoryType() == 5 || item.inventoryType() == 20);
        boolean dress =
                robe
                        || !chest
                                && items.stream()
                                        .anyMatch(
                                                item ->
                                                        item.inventoryType() == 7
                                                                && item.geosets().size() == 6
                                                                && item.geosets().get(2) > 0);
        var tabard =
                items.stream()
                        .filter(
                                item ->
                                        item.inventoryType() == RaceAppearancePlan.TABARD
                                                && item.geosets().size() == 6)
                        .findFirst()
                        .orElse(null);
        boolean showsTabard = tabard != null && !dress && tabard.geosets().get(0) > 0;
        boolean tabardTorso = tabard != null && (tabard.flags() & TABARD_KEEPS_TORSO) == 0;
        boolean clipsTabard =
                items.stream()
                        .anyMatch(
                                item ->
                                        item.inventoryType() == RaceAppearancePlan.WAIST
                                                && (item.flags() & CLIPS_LOWER_TABARD) != 0);
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
                    if (!showsTabard) {
                        set(visible, mesh, 10, 1 + g.get(1));
                    }
                    set(visible, mesh, 13, 1 + g.get(2));
                    if (!tabardTorso) {
                        set(visible, mesh, 22, 1 + g.get(3));
                    }
                    set(visible, mesh, 28, 1 + g.get(4));
                }
                case RaceAppearancePlan.TABARD -> {
                    if (showsTabard) {
                        set(visible, mesh, 12, clipsTabard ? 3 : 1 + g.get(0), true);
                    }
                    if (tabardTorso) {
                        set(visible, mesh, 22, 2, true);
                    }
                }
                case RaceAppearancePlan.CAPE -> {
                    if (g.get(0) > 0) {
                        set(visible, mesh, 15, 1 + g.get(0), true);
                    }
                }
                case 7 -> {
                    if (!showsTabard || g.get(0) > 2) {
                        set(visible, mesh, 11, 1 + g.get(0));
                    }
                    set(visible, mesh, 9, 1 + g.get(1));
                    if (!robe) {
                        set(visible, mesh, 13, 1 + g.get(2));
                    }
                }
                case 8 -> {
                    set(visible, mesh, 5, robe ? 1 : 1 + g.get(0), robe);
                    set(
                            visible,
                            mesh,
                            20,
                            g.get(1) == RaceAppearancePlan.KEEP_GROUP
                                    ? 0
                                    : g.get(1) == 0 ? 2 : g.get(1));
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

    /**
     * A variant at or below 0 comes from ItemDisplayInfo GeosetGroup -1 ({@link
     * RaceAppearancePlan#KEEP_GROUP}) and leaves the group as it is.
     */
    private static void set(
            Set<Integer> visible, PreviewMesh mesh, int group, int variant, boolean force) {
        if (variant <= 0) {
            return;
        }
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
