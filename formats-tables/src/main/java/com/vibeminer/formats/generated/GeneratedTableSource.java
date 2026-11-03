package com.vibeminer.formats.generated;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.db2.NativeRowIssueOrigin;
import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.internal.Json;
import java.nio.charset.StandardCharsets;

/**
 * Content/definition/options identity shared across builds, scoped only for encrypted encodings.
 */
public record GeneratedTableSource(
        String locale,
        String contentKey,
        String definition,
        Db2Types.Limits readerLimits,
        String encodingScope,
        IssuePolicy issuePolicy) {
    public enum IssuePolicy {
        UNKNOWN,
        NATIVE_V1
    }

    public static final String NATIVE_IDENTITY_FORMAT = "vibeminer-generated-table-native v1";

    public GeneratedTableSource(
            String locale,
            String contentKey,
            String definition,
            Db2Types.Limits readerLimits,
            String encodingScope) {
        this(locale, contentKey, definition, readerLimits, encodingScope, IssuePolicy.UNKNOWN);
    }

    public static final String IDENTITY_FORMAT = "vibeminer-generated-table v2",
            READER_REVISION = "db2-values-v3",
            ROW_ENCODING = "rows-v1";

    public GeneratedTableSource {
        if (locale != null) {
            WowLocale.parse(locale);
        }
        if (contentKey == null
                || !contentKey.matches("[0-9a-f]{32}")
                || definition == null
                || !definition.matches("[ -~]{1,512}")
                || encodingScope != null && !encodingScope.matches("[ -~]{1,512}")) {
            throw new IllegalArgumentException("Invalid generated source identity");
        }
        java.util.Objects.requireNonNull(readerLimits);
        java.util.Objects.requireNonNull(issuePolicy);
        if (issuePolicy == IssuePolicy.NATIVE_V1 && !definition.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Native source requires a canonical resolved-block digest");
        }
    }

    public static GeneratedTableSource forBlock(
            Block block, String locale, String contentKey, Db2Types.Limits limits, String scope) {
        return new GeneratedTableSource(
                ResolvedDefinition.hasLocstrings(block) ? locale : null,
                contentKey,
                ResolvedDefinition.hash(block),
                limits,
                scope);
    }

    public static GeneratedTableSource forNativeBlock(
            Block block, String locale, String contentKey, Db2Types.Limits limits, String scope) {
        var legacy = forBlock(block, locale, contentKey, limits, scope);
        return new GeneratedTableSource(
                legacy.locale(),
                contentKey,
                legacy.definition(),
                limits,
                scope,
                IssuePolicy.NATIVE_V1);
    }

    public GeneratedTableSource legacy() {
        return new GeneratedTableSource(
                locale, contentKey, definition, readerLimits, encodingScope);
    }

    public String readerOptions() {
        return NativeIssueIdentity.readerOptions(readerLimits);
    }

    public byte[] identityBytes(boolean scoped) {
        if (scoped && encodingScope == null) {
            throw new IllegalArgumentException("Encrypted source requires encoding scope");
        }
        if (issuePolicy == IssuePolicy.UNKNOWN) {
            return (scoped ? scopedIdentityText() : identityText())
                    .getBytes(StandardCharsets.UTF_8);
        }
        return NativeIssueIdentity.tuple(
                NATIVE_IDENTITY_FORMAT,
                NativeRowIssueOrigin.POLICY_ID,
                NativeRowIssueOrigin.PRODUCER_ID,
                locale,
                contentKey,
                definition,
                readerOptions(),
                READER_REVISION,
                ROW_ENCODING,
                scoped ? encodingScope : null);
    }

    public String identityText() {
        if (issuePolicy == IssuePolicy.NATIVE_V1) {
            return "native-tuple-hex=" + java.util.HexFormat.of().formatHex(identityBytes(false));
        }
        return String.join(
                "\n",
                IDENTITY_FORMAT,
                locale == null ? "-" : locale,
                contentKey,
                definition,
                "reader-options=" + readerOptions(),
                READER_REVISION,
                ROW_ENCODING);
    }

    public String scopedIdentityText() {
        if (encodingScope == null) {
            return null;
        }
        if (issuePolicy == IssuePolicy.NATIVE_V1) {
            return "native-tuple-hex=" + java.util.HexFormat.of().formatHex(identityBytes(true));
        }
        return identityText() + "\nencoding-in=" + encodingScope;
    }

    public String baseHash(boolean scoped) {
        return Json.sha256(identityBytes(scoped));
    }
}
