package com.vibeminer.concepts.races;

import com.vibeminer.rendering.models.MeshAppearance;
import java.util.List;
import java.util.Set;

/** Static textured sample, with explicit starting-clothing and source limitations. */
public final class RaceAppearanceTypes {
    private RaceAppearanceTypes() {}

    public record Item(long itemId, int inventoryType, long displayId) {}

    public record Result(
            RaceTypes.State state,
            MeshAppearance appearance,
            Set<Integer> visibleParts,
            List<Item> items,
            List<String> issues,
            boolean restored,
            String message) {
        public Result {
            visibleParts = Set.copyOf(visibleParts);
            items = List.copyOf(items);
            issues = List.copyOf(issues);
        }
    }
}
