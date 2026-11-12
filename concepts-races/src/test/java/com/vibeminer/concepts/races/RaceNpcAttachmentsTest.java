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

    private static PreviewMesh withParts(int... ids) {
        var parts = new java.util.ArrayList<PreviewMesh.Part>();
        for (int id : ids) {
            parts.add(new PreviewMesh.Part(id, 0, 3));
        }
        return new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2}, parts);
    }

    @Test
    void attachedHelmetsAndShouldersAreNotCountedAsUndrawn() {
        int head = RaceNpcAttachments.FIRST_PART + RaceNpcAttachments.HEAD * 100;
        int right = RaceNpcAttachments.FIRST_PART + RaceNpcAttachments.SHOULDER_RIGHT * 100;
        int left = RaceNpcAttachments.FIRST_PART + RaceNpcAttachments.SHOULDER_LEFT * 100;
        // Nothing attached: every counted item model stays undrawn.
        assertEquals(0, RaceNpcAttachments.drawnSlots(withParts(0, 1301)));
        // A helmet with two batches is one slot; both shoulders (or either side) are one slot.
        assertEquals(1, RaceNpcAttachments.drawnSlots(withParts(0, head, head + 1)));
        assertEquals(1, RaceNpcAttachments.drawnSlots(withParts(0, right, left, left + 1)));
        assertEquals(1, RaceNpcAttachments.drawnSlots(withParts(0, left)));
        assertEquals(2, RaceNpcAttachments.drawnSlots(withParts(0, head, right)));
        // A helmet and shoulders counted, only the helmet attached: the shoulders stay undrawn.
        var helmetOnly =
                new com.vibeminer.rendering.models.MeshAppearance(
                        withParts(0, head),
                        new float[6],
                        new float[0],
                        List.of(),
                        List.of(),
                        List.of());
        assertEquals(1, RaceNpcAttachments.undrawnAttachments(2, helmetOnly));
        assertEquals(0, RaceNpcAttachments.undrawnAttachments(1, helmetOnly));
        assertEquals(3, RaceNpcAttachments.undrawnAttachments(3, null));
    }

    @Test
    void shouldersTakeBothSidesFromOneResourceWhenOnlyOneIsNamed() {
        long[] none = {0, 0};
        assertEquals(
                List.of(
                        new RaceNpcAttachments.Piece(
                                1, 10, 20, RaceNpcAttachments.SHOULDER_LEFT, 0),
                        new RaceNpcAttachments.Piece(
                                1, 11, 21, RaceNpcAttachments.SHOULDER_RIGHT, 1)),
                RaceNpcAttachments.pieces(1, new long[] {10, 11}, new long[] {20, 21}));
        // Only ModelResourcesID[0]: the right side comes from it too, at position 1.
        assertEquals(
                List.of(
                        new RaceNpcAttachments.Piece(
                                1, 10, 20, RaceNpcAttachments.SHOULDER_LEFT, 0),
                        new RaceNpcAttachments.Piece(
                                1, 10, 20, RaceNpcAttachments.SHOULDER_RIGHT, 1)),
                RaceNpcAttachments.pieces(1, new long[] {10, 0}, new long[] {20, 0}));
        assertEquals(
                List.of(new RaceNpcAttachments.Piece(0, 30, 40, RaceNpcAttachments.HEAD, -1)),
                RaceNpcAttachments.pieces(0, new long[] {30, 0}, new long[] {40, 0}));
        assertEquals(List.of(), RaceNpcAttachments.pieces(1, none, none));
        assertEquals(List.of(), RaceNpcAttachments.pieces(4, new long[] {10, 11}, none));
    }

    @Test
    void helmetRowsApplyToTheirOwnRaceOrToEveryRace() {
        org.junit.jupiter.api.Assertions.assertTrue(RaceNpcAttachments.hidesFor(1, 1));
        org.junit.jupiter.api.Assertions.assertTrue(RaceNpcAttachments.hidesFor(0, 3));
        org.junit.jupiter.api.Assertions.assertFalse(RaceNpcAttachments.hidesFor(2, 1));
    }

    @Test
    void aTextureNotReadableYetIsAskedAgainNeverSavedAsLeftOut() {
        assertEquals(
                RaceNpcAttachments.TextureRead.LATER,
                RaceNpcAttachments.textureRead(
                        failed(com.vibeminer.files.FileTypes.FileReadState.NEEDS_NETWORK)));
        assertEquals(
                RaceNpcAttachments.TextureRead.LEFT_OUT,
                RaceNpcAttachments.textureRead(
                        failed(com.vibeminer.files.FileTypes.FileReadState.UNKNOWN_FILE)));
    }

    private static com.vibeminer.formats.images.BlpImageTypes.Result failed(
            com.vibeminer.files.FileTypes.FileReadState state) {
        return new com.vibeminer.formats.images.BlpImageTypes.Result(
                com.vibeminer.formats.images.BlpImageTypes.State.FILE_FAILED,
                null,
                null,
                List.of(),
                state,
                "FILES",
                "not stored",
                null);
    }
}
