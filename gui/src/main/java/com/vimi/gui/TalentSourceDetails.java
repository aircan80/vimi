package com.vimi.gui;

import com.vibeminer.concepts.talents.TalentTypes;
import com.vibeminer.formats.images.BlpImageTypes;

/** User-facing explanations from reported evidence; never infers absence or encryption from null. */
final class TalentSourceDetails {
    private TalentSourceDetails() {}

    static String icon(long id, BlpImageTypes.Result result) {
        if (result == null) return "Icon: unavailable · no detailed source result was returned.\nFileDataID: " + id;
        String reason = switch (result.state()) {
            case READY -> result.image() == null ? "image response contains no pixels" : "decoded image available";
            case PROTECTED -> result.holeKeys().isEmpty()
                    ? "protected image bytes are unreadable; no missing-key identifiers were reported"
                    : "encrypted image bytes require decryption keys that are unavailable";
            case UNSUPPORTED -> "image format is not supported by this viewer yet";
            case INVALID -> "image data failed format validation";
            case MIP_UNAVAILABLE -> "no supported image resolution is available";
            case LIMIT_EXCEEDED -> "image exceeds the supported preview limits";
            case FILE_FAILED -> fileReason(result);
        };
        var text = new StringBuilder("Icon: ").append(reason).append(".\nFileDataID: ").append(id)
                .append("\nImage result: ").append(result.state());
        if (result.fileState() != null) text.append("\nFile result: ").append(result.fileState());
        if (result.code() != null) text.append("\nCode: ").append(shortText(result.code(), 96));
        if (result.message() != null) text.append("\nSource message: ").append(shortText(result.message(), 768));
        if (!result.holeKeys().isEmpty()) {
            text.append("\nMissing key identifiers: ");
            text.append(result.holeKeys().stream().limit(16).map(value -> shortText(value, 64))
                    .collect(java.util.stream.Collectors.joining(", ")));
            if (result.holeKeys().size() > 16) text.append(" …");
        }
        return text.toString();
    }

    private static String fileReason(BlpImageTypes.Result result) {
        if (result.fileState() == null) return "file request failed; the source did not report a precise cause";
        return switch (result.fileState()) {
            case NEEDS_NETWORK -> "requires a download; a local copy was not available";
            case MISSING -> "file bytes were not available from the configured source";
            case UNKNOWN_FILE -> "file reference could not be resolved in the selected build";
            case NOT_IN_LOCALE -> "not available for the selected language";
            case NOT_IN_ENCODING -> "referenced content is not listed in the available encoding data";
            case PROTECTED, PARTIAL -> "required file bytes are protected or incomplete; see the reported details";
            case WRONG_KEY -> "decryption or key validation failed";
            case CORRUPT -> "file data failed integrity validation";
            case UNSUPPORTED -> "file encoding is not supported yet";
            case TOO_LARGE -> "file exceeds the supported read limits";
            case STORE_FAILED -> "local storage operation failed";
            case UNAVAILABLE -> "source is unavailable; no more specific cause was reported";
            case COMPLETE -> "file was read, but an image could not be produced";
        };
    }

    static String identity(com.vibeminer.concepts.talents.TalentTypes.Talent talent) {
        String placement = talent.row() < 0
                ? "not placed on the tree grid"
                : "tier " + (talent.row() + 1) + " · column " + (talent.column() + 1);
        var origin = talent.origin();
        if (origin.model() != com.vibeminer.concepts.talents.TalentTypes.Model.TRAIT_TREE)
            return "Source: legacy Talent table · Talent ID " + talent.id() + " · " + placement;
        return "Source: TraitTree " + origin.traitTreeId() + " · TraitNode " + talent.id()
                + " · TraitNodeEntry " + origin.traitNodeEntryId()
                + " · TraitDefinition " + origin.traitDefinitionId() + " · " + placement
                + "\nRecorded position: " + origin.rawPosX() + ", " + origin.rawPosY()
                + (origin.overridesSpellId() == null ? "" : "\nOverrides spell ID " + origin.overridesSpellId())
                + (origin.nameOverridden() ? "\nName from TraitDefinition override" : "")
                + (origin.descriptionOverridden() ? "\nDescription from TraitDefinition override" : "");
    }

    static String spells(com.vibeminer.concepts.talents.TalentTypes.Talent talent) {
        if (talent.origin().model() == com.vibeminer.concepts.talents.TalentTypes.Model.TRAIT_TREE)
            return "Spell ID: " + talent.rankSpells().getFirst() + " · maximum rank " + talent.maxRanks()
                    + " (one trait definition spell for all ranks)";
        return "Rank spell IDs: " + talent.rankSpells();
    }

    static String missingIconReference() {
        return "Icon: no usable file reference was resolved from SpellMisc for this rank. "
                + "The cause is unconfirmed; this does not establish that an icon is absent or encrypted.";
    }

    /** The reported reason a talent has no icon reference, or the unconfirmed-cause text. */
    static String missingIconReference(TalentTypes.Talent talent) {
        return talent.iconIssue() == null ? missingIconReference()
                : "Icon: no usable file reference. " + talent.iconIssue()
                        + " This does not establish that an icon is absent or encrypted.";
    }

    static String missingIconText(TalentTypes.Talent talent) {
        if (talent.iconFileDataId() != null) return "Talent icon pending";
        return talent.iconIssue() == null ? "Talent icon unavailable"
                : "Talent icon unavailable: " + shortText(talent.iconIssue(), 240);
    }

    static String buildLabel(String version) {
        return version == null || version.isBlank() ? "" : "Source build " + version;
    }

    /**
     * Classic point gate for legacy Talent rows: tier N (zero-based) needs 5 × N points in the
     * tree. TraitTree tier gates (TraitCond) are not interpreted, so none is shown for them.
     */
    static String pointRequirement(TalentTypes.Tree tree, TalentTypes.Talent talent) {
        if (talent.origin().model() != TalentTypes.Model.LEGACY_TALENT || talent.row() <= 0) return null;
        String name = tree.name() == null || tree.name().isBlank() ? "this tree" : tree.name() + " Talents";
        return "Requires " + talent.row() * 5 + " points in " + name;
    }

    /** Game wording: legacy edges name the points needed; trait edges record no rank. */
    static String prerequisite(TalentTypes.Prerequisite edge, java.util.Map<Long, TalentTypes.Talent> byId) {
        var target = byId.get(edge.talentId());
        String name = target != null ? target.name() : "unavailable talent #" + edge.talentId();
        if (edge.rankIndex() == null) return "Requires " + name;
        int points = edge.rankIndex() + 1;
        return "Requires " + points + (points == 1 ? " point in " : " points in ") + name;
    }

    /** The hover tooltip: the same facts as the detail rail, without loading anything. */
    static String hoverText(TalentTypes.Tree tree, TalentTypes.Talent talent,
            java.util.Map<Long, TalentTypes.Talent> byId) {
        var text = new StringBuilder(talent.name()).append("\nMaximum rank ").append(talent.maxRanks());
        String points = pointRequirement(tree, talent);
        if (points != null) text.append('\n').append(points);
        for (var edge : talent.prerequisites()) text.append('\n').append(prerequisite(edge, byId));
        String description = talent.description();
        if (description == null || description.isBlank())
            text.append("\nDescription unavailable in the readable data.");
        else if (description.contains("$") || description.contains("|"))
            text.append("\nSelect to show the description with its values.");
        else text.append('\n').append(shortText(description, 600));
        if (talent.iconFileDataId() == null && talent.iconIssue() != null)
            text.append("\nIcon: ").append(shortText(talent.iconIssue(), 240));
        return text.append("\nSelect for details and source").toString();
    }

    static String missingDescription() {
        return "Description: no readable text was returned for this rank. "
                + "The source did not report a precise cause for this talent; class-level notices below may provide context.";
    }

    static String shortText(String text, int maximum) {
        if (text == null) return "";
        return text.length() <= maximum ? text : text.substring(0, maximum) + "…";
    }
}
