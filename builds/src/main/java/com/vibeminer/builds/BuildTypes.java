package com.vibeminer.builds;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.RangePlanner;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Immutable layer 3 build-opening and resolution boundary. */
public final class BuildTypes {
    private BuildTypes() {}

    public record BuildRef(String product, String buildConfig, String cdnConfig, String region) {
        public BuildRef {
            if (!Products.isWow(product)) {
                throw new IllegalArgumentException("Not a WoW product id");
            }
            if (region != null && !region.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Invalid region");
            }
            buildConfig = hex(buildConfig);
            cdnConfig = hex(cdnConfig);
        }

        public BuildRef(String product, String buildConfig, String cdnConfig) {
            this(product, buildConfig, cdnConfig, null);
        }

        private static String hex(String value) {
            if (value == null || !value.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Config key must be 32 hex digits");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }

    public enum BuildOpenState {
        OPEN,
        NEEDS_NETWORK,
        UNAVAILABLE,
        MISSING,
        ENCRYPTED,
        CORRUPT,
        UNSUPPORTED,
        STORE_FAILED
    }

    public enum BuildPart {
        BUILD_CONFIG,
        CDN_CONFIG,
        ENCODING,
        ROOT,
        ARCHIVE_INDEX,
        FILE_INDEX
    }

    public record BuildOpenResult(
            BuildRef build,
            BuildOpenState state,
            Build opened,
            BuildPart part,
            String code,
            String message,
            Instant observedAt) {}

    /** Authenticated configurations, ENCODING and ROOT facts; archive indexes are not required. */
    public record MetadataOpenResult(
            BuildRef build,
            BuildOpenState state,
            BuildMetadata metadata,
            BuildPart part,
            String code,
            String message,
            Instant observedAt) {}

    public record BuildMetadata(
            String buildName,
            CdnKey rootContentKey,
            CdnKey encodingContentKey,
            CdnKey encodingEncodedKey,
            int archiveCount,
            int fileCount,
            List<String> locales) {
        public BuildMetadata {
            locales = List.copyOf(locales);
        }
    }

    public enum FileResolutionState {
        FOUND,
        UNKNOWN_FILE,
        NOT_IN_ENCODING
    }

    public record FileVariant(
            long localeFlags,
            long contentFlags,
            CdnKey contentKey,
            Long decodedSize,
            CdnKey encodedKey,
            Long encodedSize,
            RangePlanner.Location location) {}

    public record FileResolution(
            FileResolutionState state, Long fileDataId, List<FileVariant> variants) {
        public FileResolution {
            variants = List.copyOf(variants);
        }
    }
}
