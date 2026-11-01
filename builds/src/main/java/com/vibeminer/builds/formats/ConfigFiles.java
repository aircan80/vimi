package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Declarations from authenticated build/CDN configs; grammar and stable codes match C#. */
public final class ConfigFiles {
    private ConfigFiles() {}

    public record KeyPair(CdnKey content, CdnKey encoded) {}

    public record Keys(CdnKey content, CdnKey encoded) {}

    public record Sizes(long decoded, long encoded) {}

    public record BuildConfig(
            CdnKey root,
            KeyPair encoding,
            Sizes encodingSize,
            Keys install,
            Sizes installSize,
            Keys download,
            Sizes downloadSize,
            Keys size,
            Sizes sizeSize,
            String buildName,
            Map<String, List<String>> fields) {}

    public record CdnConfig(
            List<CdnKey> archives,
            List<Long> archiveIndexSizes,
            CdnKey fileIndex,
            Long fileIndexSize,
            CdnKey archiveGroup,
            Map<String, List<String>> fields) {
        public CdnConfig {
            archives = List.copyOf(archives);
            if (archiveIndexSizes != null) {
                archiveIndexSizes = List.copyOf(archiveIndexSizes);
            }
        }
    }

    private record Entry(Keys keys, Sizes sizes) {}

    public static BuildConfig build(byte[] bytes, Runnable cancellation) {
        String code = "BUILD_CONFIG_INVALID";
        var fields = TactConfig.parse(bytes, cancellation).fields();
        var root = fields.get("root");
        if (root == null || root.size() != 1) {
            throw FormatSupport.corrupt(code, "Build config needs one root key");
        }
        CdnKey rootKey = key(code, root.getFirst());
        if (fields.containsKey("root-size")) {
            throw FormatSupport.unsupported(
                    "BUILD_CONFIG_UNSUPPORTED", "VFS root-size layout is unsupported");
        }
        Entry encoding = entry(fields, "encoding"),
                install = entry(fields, "install"),
                download = entry(fields, "download"),
                size = entry(fields, "size");
        if (encoding.keys() == null || encoding.keys().encoded() == null) {
            throw FormatSupport.corrupt(code, "Encoding requires content and encoded keys");
        }
        if (install.keys() == null) {
            throw FormatSupport.corrupt(code, "Build config has no install");
        }
        List<String> name = fields.get("build-name");
        return new BuildConfig(
                rootKey,
                new KeyPair(encoding.keys().content(), encoding.keys().encoded()),
                encoding.sizes(),
                install.keys(),
                install.sizes(),
                download.keys(),
                download.sizes(),
                size.keys(),
                size.sizes(),
                name == null || name.isEmpty() ? null : String.join(" ", name),
                fields);
    }

    private static Entry entry(Map<String, List<String>> fields, String role) {
        String code = "BUILD_CONFIG_INVALID";
        var keys = fields.get(role);
        var sizes = fields.get(role + "-size");
        if (keys == null) {
            if (sizes != null) {
                throw FormatSupport.corrupt(code, "Size declared without its object");
            }
            return new Entry(null, null);
        }
        if (keys.isEmpty() || keys.size() > 2) {
            throw FormatSupport.corrupt(code, "Object needs one or two keys");
        }
        Keys entry =
                new Keys(
                        key(code, keys.getFirst()),
                        keys.size() == 2 ? key(code, keys.get(1)) : null);
        if (sizes == null) {
            if (keys.size() == 2) {
                throw FormatSupport.corrupt(code, "Both keys need size declarations");
            }
            return new Entry(entry, null);
        }
        if (sizes.size() != 2) {
            throw FormatSupport.corrupt(code, "Size declaration needs decoded and encoded lengths");
        }
        Sizes pair = new Sizes(length(code, sizes.getFirst()), length(code, sizes.get(1)));
        if (pair.decoded() > Blte.MAX_DECODED_BYTES || pair.encoded() > Blte.MAX_ENCODED_BYTES) {
            throw FormatSupport.corrupt("BUILD_CONFIG_LIMIT", "Object sizes exceed BLTE limits");
        }
        return new Entry(entry, pair);
    }

    public static CdnConfig cdn(byte[] bytes, Runnable cancellation) {
        String code = "CDN_CONFIG_INVALID";
        var fields = TactConfig.parse(bytes, cancellation).fields();
        var listed = fields.get("archives");
        if (listed == null) {
            throw FormatSupport.corrupt(code, "CDN config has no archives");
        }
        List<CdnKey> archives = new ArrayList<>();
        for (String value : listed) {
            cancellation.run();
            archives.add(key(code, value));
        }
        List<Long> sizes = null;
        if (fields.containsKey("archives-index-size")) {
            var values = fields.get("archives-index-size");
            if (values.size() != archives.size()) {
                throw FormatSupport.corrupt(code, "Archive size hints differ in count");
            }
            sizes = values.stream().map(v -> length(code, v)).toList();
        }
        CdnKey fileIndex = singleKey(fields, "file-index", code),
                group = singleKey(fields, "archive-group", code);
        Long fileSize = null;
        if (fields.containsKey("file-index-size")) {
            var value = fields.get("file-index-size");
            if (value.size() != 1 || fileIndex == null) {
                throw FormatSupport.corrupt(code, "Invalid file-index-size");
            }
            fileSize = length(code, value.getFirst());
        }
        return new CdnConfig(archives, sizes, fileIndex, fileSize, group, fields);
    }

    private static CdnKey singleKey(Map<String, List<String>> fields, String name, String code) {
        var values = fields.get(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1) {
            throw FormatSupport.corrupt(code, "Expected one key for " + name);
        }
        return key(code, values.getFirst());
    }

    private static CdnKey key(String code, String value) {
        if (!value.matches("[0-9a-fA-F]{32}") || value.equals("0".repeat(32))) {
            throw FormatSupport.corrupt(code, "Invalid metadata key");
        }
        return CdnKey.parse(value);
    }

    private static long length(String code, String value) {
        if (!value.matches("[1-9][0-9]*")) {
            throw FormatSupport.corrupt(code, "Invalid canonical length");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw FormatSupport.corrupt(code, "Length exceeds signed64");
        }
    }
}
