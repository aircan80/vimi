package com.vibeminer.formats.tables;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.definitions.DbdTypes.BuildVersion;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import com.vibeminer.formats.internal.Json;
import java.util.Objects;

/** Pinned local input only. Caller keeps the immutable source alive through table-view drain. */
public record HotfixBinding(
        BuildRef build,
        String locale,
        String version,
        Db2ByteSource source,
        HotfixCacheReader.Index index) {
    public static final String POLICY = "hotfix-effective-conservative-v1";

    public HotfixBinding {
        Objects.requireNonNull(build);
        Objects.requireNonNull(source);
        Objects.requireNonNull(index);
        WowLocale.parse(locale);
        var parsed = BuildVersion.parse(version);
        if (parsed.build() != index.build()
                || !index.isFor(source)
                || source.length() != index.length()) {
            throw new IllegalArgumentException("Hotfix input does not match selected build");
        }
    }

    public String inputFingerprint() {
        return Json.sha256(
                String.join(
                                "\n",
                                POLICY,
                                build.product(),
                                build.buildConfig(),
                                build.cdnConfig(),
                                build.region() == null ? "" : build.region(),
                                locale,
                                version,
                                index.sha256())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
