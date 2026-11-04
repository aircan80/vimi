package com.vimi.gui;

import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;

/** Reported image causes stay distinct; null evidence never becomes confirmed absence/encryption. */
public final class TalentSourceDetailsTest {
    private TalentSourceDetailsTest() {}
    public static void main(String[] args) {
        String protectedImage = TalentSourceDetails.icon(201, result(BlpImageTypes.State.PROTECTED,
                null, List.of("0011223344556677"), null, "Encrypted bytes"));
        require(protectedImage.contains("encrypted") && protectedImage.contains("0011223344556677"),
                "Confirmed protection exposes reported key identifiers");
        String unsupported = TalentSourceDetails.icon(202, result(BlpImageTypes.State.UNSUPPORTED,
                null, List.of(), "BLP_UNSUPPORTED", "Unsupported mip flags 2"));
        require(unsupported.contains("not supported") && unsupported.contains("BLP_UNSUPPORTED")
                && !unsupported.contains("encrypted"), "Unsupported image format is not encryption");
        String download = TalentSourceDetails.icon(203, result(BlpImageTypes.State.FILE_FAILED,
                FileTypes.FileReadState.NEEDS_NETWORK, List.of(), "OFFLINE", "Local copy absent"));
        require(download.contains("requires a download") && !download.contains("encrypted"),
                "Local download requirement is distinct from protection");
        String unresolved = TalentSourceDetails.icon(204, result(BlpImageTypes.State.FILE_FAILED,
                FileTypes.FileReadState.UNKNOWN_FILE, List.of(), null, null));
        require(unresolved.contains("could not be resolved") && !unresolved.contains("does not exist"),
                "An unresolved reference does not establish global absence");
        require(TalentSourceDetails.icon(205, null).contains("no detailed source result"),
                "Missing result retains uncertainty");
        require(TalentSourceDetails.missingIconReference().contains("cause is unconfirmed"),
                "Missing reference carries no invented cause");
        require(TalentSourceDetails.missingDescription().contains("did not report a precise cause"),
                "Missing description carries no invented cause");
        String bounded = TalentSourceDetails.icon(206, result(BlpImageTypes.State.PROTECTED, null,
                java.util.Collections.nCopies(100, "a".repeat(200)), "b".repeat(1000), "c".repeat(10000)));
        require(bounded.length() < 2400, "Untrusted result strings and key identifiers are bounded");
        var trait = new com.vibeminer.concepts.talents.TalentTypes.Talent(104923, 0, 1, List.of(16814L), 5,
                List.of(), "Trait", null, null, new com.vibeminer.concepts.talents.TalentTypes.Origin(
                        com.vibeminer.concepts.talents.TalentTypes.Model.TRAIT_TREE, 1089L, 129583L, 134384L,
                        1620L, 2130L, null, false, false));
        String identity = TalentSourceDetails.identity(trait);
        require(identity.contains("TraitTree 1089") && identity.contains("TraitNode 104923")
                && identity.contains("1620, 2130") && identity.contains("tier 1 · column 2"),
                "Trait talents identify their TraitTree source rows and recorded position");
        require(TalentSourceDetails.spells(trait).contains("maximum rank 5"),
                "A trait definition spell is not presented as one spell per rank");
        var legacy = new com.vibeminer.concepts.talents.TalentTypes.Talent(7, -1, -1, List.of(1L, 2L), List.of(),
                "Legacy", null, null);
        require(TalentSourceDetails.identity(legacy).contains("legacy Talent table")
                && TalentSourceDetails.identity(legacy).contains("not placed"),
                "Legacy rows and unplaced talents stay distinct");
        System.out.println("Talent source details PASS: protected, unsupported, download, unresolved, bounded evidence and trait source rows");
    }

    private static BlpImageTypes.Result result(BlpImageTypes.State state,
            FileTypes.FileReadState fileState, List<String> keys, String code, String message) {
        return new BlpImageTypes.Result(state, null, BlpImageTypes.Cache.NONE, keys,
                fileState, code, message, null);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
