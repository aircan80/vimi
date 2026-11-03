package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.DocumentKind;
import java.net.URI;
import java.util.Set;

/** Fixed sources from the pinned seven-layer reference. No caller-supplied download host. */
public final class SourceUrls {
    public static final int DOCUMENT_BYTES = 1 << 20,
            COMMIT_BYTES = 256 << 10,
            PART_BYTES = 8 << 20,
            MAX_PARTS = 64;
    public static final long MAX_ASSET_BYTES = 512L << 20;
    public static final String ASSET_NAME = "community-listfile-withcapitals.csv";
    public static final URI TACT_KEYS =
            URI.create("https://raw.githubusercontent.com/wowdev/TACTKeys/master/WoW.txt");
    public static final int TACT_KEY_BYTES = 4 << 20;
    public static final URI LATEST_RELEASE =
            URI.create("https://api.github.com/repos/wowdev/wow-listfile/releases/latest");
    public static final Set<String> ASSET_HOSTS =
            Set.of("release-assets.githubusercontent.com", "objects.githubusercontent.com");

    public static final URI EG_LINK_HEAD =
            URI.create(
                    "https://api.github.com/repos/JIVESCORP/eg-link-output-wowf/git/ref/heads/main");

    public static URI egLink(String revision, String path) {
        if (revision == null
                || !revision.matches("[0-9a-f]{40}")
                || path == null
                || !(path.equals("data/index.json")
                        || path.matches(
                                "data/[a-z0-9_]{1,40}/[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,10}/[a-z][a-z0-9_]{0,63}(\\.[a-z]{2}[A-Z]{2})?\\.json"))) {
            throw new IllegalArgumentException("Invalid EG Link commit or dataset path");
        }
        return URI.create(
                "https://raw.githubusercontent.com/JIVESCORP/eg-link-output-wowf/"
                        + revision
                        + "/"
                        + path);
    }

    private SourceUrls() {}

    public static URI version(String product, String region, DocumentKind kind) {
        java.util.Objects.requireNonNull(kind);
        if (region == null
                || !Set.of("us", "eu", "kr", "tw").contains(region)
                || (kind == DocumentKind.SUMMARY
                        ? !"".equals(product)
                        : product == null || !product.matches("[a-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Invalid product, region or document kind");
        }
        return URI.create(
                "https://"
                        + region
                        + ".version.battle.net/v2/"
                        + (kind == DocumentKind.SUMMARY
                                ? "summary"
                                : "products/"
                                        + product
                                        + "/"
                                        + kind.name().toLowerCase(java.util.Locale.ROOT)));
    }

    public static String table(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid definition table name");
        }
        return name;
    }

    public static URI commits(String table) {
        return URI.create(
                "https://api.github.com/repos/wowdev/WoWDBDefs/commits?path=definitions/"
                        + table(table)
                        + ".dbd&sha=master&per_page=1");
    }

    public static URI definition(String revision, String table) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid definition commit");
        }
        return URI.create(
                "https://raw.githubusercontent.com/wowdev/WoWDBDefs/"
                        + revision
                        + "/definitions/"
                        + table(table)
                        + ".dbd");
    }

    public static URI asset(String tag) {
        if (tag == null || !tag.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid listfile release tag");
        }
        return URI.create(
                "https://github.com/wowdev/wow-listfile/releases/download/"
                        + tag
                        + "/"
                        + ASSET_NAME);
    }

    static boolean assetRedirect(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && "https".equals(uri.getScheme())
                && uri.getHost() != null
                && ASSET_HOSTS.contains(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null;
    }
}
