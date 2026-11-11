package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RaceNpcAttachmentsTest {
    @Test
    void aHelmetHidesItsGroupsButNeverTheBodyAndItemPartsAreShown() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2, 0, 1, 2, 0, 1, 2},
                        List.of(
                                new PreviewMesh.Part(0, 0, 3),
                                new PreviewMesh.Part(5, 3, 3),
                                new PreviewMesh.Part(RaceNpcAttachments.FIRST_PART + 2, 6, 3)));
        // Group 0 (hair) and 7 (ears) hidden: part 0 is the body and stays; 3202 stays.
        assertEquals(
                Set.of(0, 3202, 1301, RaceNpcAttachments.FIRST_PART + 2),
                RaceNpcAttachments.visible(Set.of(0, 5, 702, 3202, 1301), Set.of(0, 7), mesh));
        assertEquals(
                Set.of(0, 5, RaceNpcAttachments.FIRST_PART + 2),
                RaceNpcAttachments.visible(Set.of(0, 5), Set.of(), mesh));
    }
}
