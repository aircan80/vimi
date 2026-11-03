package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Compact evidence provenance; deliberately excluded from semantic schema/field admission. */
final class IssueEvidenceMetadata {
    private IssueEvidenceMetadata() {}

    static void put(Map<String, String> target, String prefix, CapturedRowsTypes.Receipt receipt) {
        var evidence = receipt.issueChannelEvidence();
        String policy = evidence.policyId() == null ? "unknown" : evidence.policyId();
        String producer = evidence.producerId() == null ? "unknown" : evidence.producerId();
        byte[] bytes =
                NativeIssueIdentity.tuple(
                        "captured-issue-evidence-v1",
                        evidence.state().name(),
                        evidence.policyId(),
                        evidence.producerId(),
                        evidence.graphIdentity(),
                        evidence.sourceIdentitySha256(),
                        evidence.resolvedBlockSha256(),
                        evidence.readerOptionsSha256(),
                        evidence.actualColumnsSha256());
        if (bytes.length > 4096) {
            throw new IllegalArgumentException("Captured evidence exceeds metadata bound");
        }
        try {
            target.put(prefix + ".state", evidence.state().name());
            target.put(prefix + ".policy", policy);
            target.put(prefix + ".producer", producer);
            target.put(
                    prefix + ".digest",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
